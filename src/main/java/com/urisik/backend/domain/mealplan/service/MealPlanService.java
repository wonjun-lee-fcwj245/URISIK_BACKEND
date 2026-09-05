package com.urisik.backend.domain.mealplan.service;

import com.urisik.backend.domain.familyroom.entity.FamilyRoom;
import com.urisik.backend.domain.familyroom.repository.FamilyRoomRepository;
import com.urisik.backend.domain.familyroom.service.FamilyRoomService;
import com.urisik.backend.domain.mealplan.ai.candidate.MealPlanCandidateProvider;
import com.urisik.backend.domain.mealplan.ai.generator.MealPlanGenerator;
import com.urisik.backend.domain.mealplan.ai.service.MealPlanAiService;
import com.urisik.backend.domain.mealplan.ai.validation.MealPlanGenerationValidator;
import com.urisik.backend.domain.mealplan.dto.common.RecipeDTO;
import com.urisik.backend.domain.mealplan.dto.req.CreateMealPlanReqDTO;
import com.urisik.backend.domain.mealplan.dto.req.CreateMealPlanReqDTO.SlotRequest;
import com.urisik.backend.domain.mealplan.dto.common.RecipeSelectionDTO;
import com.urisik.backend.domain.mealplan.dto.req.UpdateMealPlanReqDTO;
import com.urisik.backend.domain.mealplan.dto.res.CreateMealPlanResDTO;
import com.urisik.backend.domain.mealplan.dto.res.UpdateMealPlanResDTO;
import com.urisik.backend.domain.mealplan.dto.res.ConfirmMealPlanResDTO;
import com.urisik.backend.domain.mealplan.entity.MealPlan;
import com.urisik.backend.domain.mealplan.enums.MealPlanStatus;
import com.urisik.backend.domain.mealplan.exception.MealPlanException;
import com.urisik.backend.domain.mealplan.exception.code.MealPlanErrorCode;
import com.urisik.backend.domain.mealplan.repository.MealPlanRepository;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.global.kafka.producer.KafkaEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MealPlanService {

    private final MealPlanRepository mealPlanRepository;
    private final FamilyRoomRepository familyRoomRepository;
    private final FamilyRoomService familyRoomService;
    private final MealPlanAiService mealPlanAiService;
    private final RecipeRepository recipeRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final MealPlanCandidateProvider candidateProvider;
    private final MealPlanGenerator generator;
    private final MealPlanGenerationValidator validator;
    private final KafkaEventProducer kafkaEventProducer;

    /** 식단 생성 API */
    @Transactional
    public CreateMealPlanResult createMealPlan(Long memberId, Long familyRoomId, CreateMealPlanReqDTO req) {
        final long t0 = System.nanoTime();
        final LocalDate reqWeekStart = req == null ? null : req.weekStartDate();
        final int reqSlotCount = (req == null || req.selectedSlots() == null) ? 0 : req.selectedSlots().size();

        try {
            LocalDate weekStart = normalizeToMonday(req.weekStartDate());

            // 방장 검증
            familyRoomService.validateLeader(memberId, familyRoomId);

            Optional<MealPlan> existingMealPlanOpt =
                    mealPlanRepository.findByFamilyRoomIdAndWeekStartDate(familyRoomId, weekStart);

            MealPlan mealPlan;
            if (existingMealPlanOpt.isPresent()) {
                mealPlan = existingMealPlanOpt.get();

                // 확정된 식단은 재생성/수정 불가
                if (mealPlan.getStatus() == MealPlanStatus.CONFIRMED) {
                    throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_ALREADY_CONFIRMED);
                }

                // DRAFT 식단이 있을 시 regenerate=false면 중복 생성 불가
                if (!req.regenerate()) {
                    throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_ALREADY_EXISTS);
                }

            } else {
                FamilyRoom familyRoom = familyRoomRepository.findById(familyRoomId)
                        .orElseThrow(() -> new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED));

                mealPlan = MealPlan.draft(familyRoom, weekStart);
            }

            List<MealPlan.SlotKey> selectedSlots = distinctPreserveOrder(toSlotKeys(req.selectedSlots()));
            if (selectedSlots.isEmpty()) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            final long tGen0 = System.nanoTime();
            GenerationResult generationResult = generateForSelectedSlots(memberId, familyRoomId, selectedSlots);
            final long genMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tGen0);

            // 저장
            final long tSave0 = System.nanoTime();
            for (var entry : generationResult.selections().entrySet()) {
                MealPlan.SlotKey slotKey = entry.getKey();
                RecipeSelectionDTO selection = entry.getValue();
                mealPlan.updateSlot(slotKey, toSlotRefType(selection.type()), selection.id());
            }
            mealPlanRepository.save(mealPlan);
            final long saveMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tSave0);

            // 응답: chosenId + title
            Map<String, RecipeDTO> slots = new LinkedHashMap<>();

            // Batch-load titles
            final long tTitle0 = System.nanoTime();
            Map<Long, String> recipeTitles = new HashMap<>();
            Map<Long, String> trTitles = new HashMap<>();
            Set<Long> recipeIdsToLoad = new HashSet<>();
            Set<Long> trIdsToLoad = new HashSet<>();
            for (RecipeSelectionDTO sel : generationResult.selections().values()) {
                if (sel.type() == RecipeSelectionDTO.RecipeSelectionType.RECIPE) recipeIdsToLoad.add(sel.id());
                else trIdsToLoad.add(sel.id());
            }
            if (!recipeIdsToLoad.isEmpty())
                recipeRepository.findAllById(recipeIdsToLoad).forEach(r -> recipeTitles.put(r.getId(), r.getTitle()));
            if (!trIdsToLoad.isEmpty())
                transformedRecipeRepository.findAllById(trIdsToLoad).forEach(tr -> trTitles.put(tr.getId(), tr.getTitle()));
            final long titleLoadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tTitle0);

            for (MealPlan.SlotKey slot : selectedSlots) {
                RecipeSelectionDTO selection = generationResult.selections().get(slot);
                if (selection == null || selection.type() == null || selection.id() == null) {
                    throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED);
                }
                String title = (selection.type() == RecipeSelectionDTO.RecipeSelectionType.RECIPE)
                        ? recipeTitles.getOrDefault(selection.id(), "UNKNOWN")
                        : trTitles.getOrDefault(selection.id(), "UNKNOWN");

                RecipeDTO.RecipeType dtoType = (selection.type() == RecipeSelectionDTO.RecipeSelectionType.RECIPE)
                        ? RecipeDTO.RecipeType.RECIPE
                        : RecipeDTO.RecipeType.TRANSFORMED_RECIPE;

                String key = slot.mealType().name() + "-" + slot.dayOfWeek().name();
                slots.put(key, new RecipeDTO(dtoType, selection.id(), title));
            }

            CreateMealPlanResDTO res = new CreateMealPlanResDTO(
                    mealPlan.getId(),
                    familyRoomId,
                    mealPlan.getWeekStartDate(),
                    mealPlan.getStatus(),
                    slots
            );

            final long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            AiMeta meta = generationResult.aiMeta();
            log.info("[PERF] mealplan_create totalMs={} genMs={} saveMs={} titleLoadMs={} weekStart={} slots={} aiUsed={} aiClient={} success=true",
                    totalMs,
                    genMs,
                    saveMs,
                    titleLoadMs,
                    weekStart,
                    selectedSlots.size(),
                    meta != null && meta.aiUsed(),
                    meta == null ? "UNKNOWN" : meta.aiClient());

            return new CreateMealPlanResult(res, generationResult.aiMeta());

        } catch (Exception e) {
            final long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            log.warn("[PERF] mealplan_create totalMs={} weekStart={} reqSlots={} familyRoomId={} errType={} msg={} success=false",
                    totalMs,
                    reqWeekStart,
                    reqSlotCount,
                    familyRoomId,
                    e.getClass().getSimpleName(),
                    e.getMessage());

            // Local debugging: include stack trace (keep PERF line above for parsing)
            log.error("[ERR] mealplan_create FAILED (memberId={}, familyRoomId={}, weekStart={}, reqSlots={})",
                    memberId,
                    familyRoomId,
                    reqWeekStart,
                    reqSlotCount,
                    e);
            throw e;
        }
    }

    private record GenerationResult(
            Map<MealPlan.SlotKey, RecipeSelectionDTO> selections,
            AiMeta aiMeta
    ) {
    }

    /**
     * 컨트롤러에서 응답 헤더에 실어줄 메타
     * - aiUsed: AI 호출 경로를 탔는지 여부(=fallback이 아닌지)
     * - aiClient: 어떤 AI 클라이언트를 사용했는지 여부
     */
    public record AiMeta(
            boolean aiUsed,
            String aiClient
    ) {
        public static AiMeta ai(String aiClient) {
            return new AiMeta(true, aiClient == null ? "UNKNOWN" : aiClient);
        }

        public static AiMeta fallback() {
            return new AiMeta(false, "FALLBACK");
        }
    }

    /** 식단 생성 결과(응답 DTO + AI 메타) */
    public record CreateMealPlanResult(
            CreateMealPlanResDTO response,
            AiMeta aiMeta
    ) {
    }

    private GenerationResult generateForSelectedSlots(
            Long memberId,
            Long familyRoomId,
            List<MealPlan.SlotKey> selectedSlots
    ) {
        int requiredSize = selectedSlots == null ? 0 : selectedSlots.size();
        if (requiredSize == 0) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }

        final long tStage0 = System.nanoTime();

        final long tCand0 = System.nanoTime();

        // CandidateProvider 역할: 안전 후보군만 제공(알레르기/제외 정책은 provider 내부에서 반영되어야 함)
        List<RecipeSelectionDTO> wishSelections =
                candidateProvider.getWishRecipeSelections(memberId, familyRoomId);

        List<RecipeSelectionDTO> fallbackSelections =
                candidateProvider.getFallbackRecipeSelections(memberId, familyRoomId);

        final long candMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tCand0);

        if ((wishSelections == null || wishSelections.isEmpty())
                && (fallbackSelections == null || fallbackSelections.isEmpty())) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED);
        }

        // 위시 1순위: 후보군 우선순위/가중치(리스트 앞쪽)
        // baseRecipe 기준 중복 제거(원형/변형이 섞여도 base가 같으면 하나만 남김)
        List<RecipeSelectionDTO> safeCandidates = mergeDistinctByBaseRecipe(wishSelections, fallbackSelections);
        if (safeCandidates.isEmpty()) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED);
        }

        // 슬롯 순서는 랜덤
        List<MealPlan.SlotKey> shuffledSlots = new ArrayList<>(selectedSlots);
        Collections.shuffle(shuffledSlots);

        log.info("[MEALPLAN][GEN] AI-first start (requiredSlots={}, wishCandidates={}, fallbackCandidates={}, safeCandidates={})",
                requiredSize,
                wishSelections == null ? 0 : wishSelections.size(),
                fallbackSelections == null ? 0 : fallbackSelections.size(),
                safeCandidates.size());

        // 안전 후보군 안에서 슬롯별 최종 선택
        try {
            final long tAssign0 = System.nanoTime();
            Map<MealPlan.SlotKey, RecipeSelectionDTO> assignments =
                    generator.generateRecipeAssignments(shuffledSlots, safeCandidates);
            final long assignMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tAssign0);

            // 후보군(type+id) 안에서 선택했는지 검증
            validator.validateRecipeSelections(shuffledSlots, assignments, safeCandidates);

            // baseRecipe 중복 금지(핵심 룰)
            validator.validateNoDuplicateBaseRecipe(assignments, this::resolveBaseRecipeId);

            if (assignments == null || assignments.size() < requiredSize) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED);
            }

            log.info("[MEALPLAN][GEN] AI assignments done (count={})", assignments.size());

            final long stageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStage0);
            log.info("[PERF] mealplan_generate stageMs={} candMs={} assignMs={} requiredSlots={} wishCandidates={} fallbackCandidates={} safeCandidates={} aiUsed=true aiClient={} success=true",
                    stageMs,
                    candMs,
                    assignMs,
                    requiredSize,
                    wishSelections == null ? 0 : wishSelections.size(),
                    fallbackSelections == null ? 0 : fallbackSelections.size(),
                    safeCandidates.size(),
                    mealPlanAiService.getAiClient());

            return new GenerationResult(assignments, AiMeta.ai(mealPlanAiService.getAiClient()));

        } catch (Exception e) {
            if (e instanceof MealPlanException) {
                // Don't fallback on validation failures — they indicate a real problem
                throw e;
            }
            // fallback: AI 실패/비활성(NoAiClient)/파싱 실패/검증 실패 등 예외 상황에서만 사용
            log.warn("[MEALPLAN][GEN] AI failed -> fallback (errType={}, msg={})",
                    e.getClass().getSimpleName(), e.getMessage());
            log.error("[ERR] mealplan_generate AI path failed -> fallback (memberId={}, familyRoomId={}, requiredSlots={})",
                    memberId,
                    familyRoomId,
                    requiredSize,
                    e);
            Map<MealPlan.SlotKey, RecipeSelectionDTO> fallbackAssignments =
                    heuristicFallbackFill(shuffledSlots, wishSelections, fallbackSelections);
            if (fallbackAssignments.size() < requiredSize) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_GENERATION_FAILED);
            }
            log.info("[MEALPLAN][GEN] fallback assignments done (count={})", fallbackAssignments.size());
            final long stageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStage0);
            log.info("[PERF] mealplan_generate stageMs={} candMs={} assignMs={} requiredSlots={} wishCandidates={} fallbackCandidates={} safeCandidates={} aiUsed=false aiClient=FALLBACK success=true",
                    stageMs,
                    candMs,
                    -1,
                    requiredSize,
                    wishSelections == null ? 0 : wishSelections.size(),
                    fallbackSelections == null ? 0 : fallbackSelections.size(),
                    safeCandidates.size());
            return new GenerationResult(fallbackAssignments, AiMeta.fallback());
        }
    }

    /**
     * 위시 후보를 우선으로 두고, baseRecipe 기준으로 중복 제거한 안전 후보군 생성
     * - Recipe baseKey = recipeId
     * - TransformedRecipe baseKey = transformed.baseRecipe.id
     */
    private List<RecipeSelectionDTO> mergeDistinctByBaseRecipe(
            List<RecipeSelectionDTO> wishSelections,
            List<RecipeSelectionDTO> fallbackSelections
    ) {
        List<RecipeSelectionDTO> merged = new ArrayList<>();
        Set<Long> seenBase = new HashSet<>();

        if (wishSelections != null) {
            for (RecipeSelectionDTO s : wishSelections) {
                if (s == null || s.id() == null || s.type() == null) continue;
                Long baseId = resolveBaseRecipeId(s);
                if (baseId == null) continue;
                if (seenBase.add(baseId)) {
                    merged.add(s);
                }
            }
        }

        if (fallbackSelections != null) {
            for (RecipeSelectionDTO s : fallbackSelections) {
                if (s == null || s.id() == null || s.type() == null) continue;
                Long baseId = resolveBaseRecipeId(s);
                if (baseId == null) continue;
                if (seenBase.add(baseId)) {
                    merged.add(s);
                }
            }
        }

        return merged;
    }

    /**
     * AI가 실패했을 때만 사용하는 fallback.
     * - 위시 우선순위는 유지하되, 슬롯 배치 순서는 랜덤
     * - baseRecipe 기준 중복 금지
     */
    private Map<MealPlan.SlotKey, RecipeSelectionDTO> heuristicFallbackFill(
            List<MealPlan.SlotKey> selectedSlots,
            List<RecipeSelectionDTO> wishSelections,
            List<RecipeSelectionDTO> fallbackSelections
    ) {
        int requiredSize = selectedSlots == null ? 0 : selectedSlots.size();

        Map<MealPlan.SlotKey, RecipeSelectionDTO> result = new LinkedHashMap<>();
        Set<Long> usedBaseRecipeIds = new HashSet<>();

        // 슬롯 채우기 순서는 랜덤
        List<MealPlan.SlotKey> shuffledSlots = new ArrayList<>(selectedSlots);
        Collections.shuffle(shuffledSlots);
        Iterator<MealPlan.SlotKey> slotIterator = shuffledSlots.iterator();

        // 위시 먼저
        if (wishSelections != null) {
            for (RecipeSelectionDTO selection : wishSelections) {
                if (!slotIterator.hasNext()) break;
                if (selection == null || selection.id() == null || selection.type() == null) continue;

                Long baseRecipeId = resolveBaseRecipeId(selection);
                if (baseRecipeId == null) continue;
                if (usedBaseRecipeIds.contains(baseRecipeId)) continue;

                usedBaseRecipeIds.add(baseRecipeId);
                result.put(slotIterator.next(), selection);
            }
        }

        // 부족분은 fallback 랜덤
        if (result.size() < requiredSize && fallbackSelections != null) {
            List<RecipeSelectionDTO> shuffled = new ArrayList<>(fallbackSelections);
            Collections.shuffle(shuffled);

            for (RecipeSelectionDTO selection : shuffled) {
                if (!slotIterator.hasNext()) break;
                if (selection == null || selection.id() == null || selection.type() == null) continue;

                Long baseRecipeId = resolveBaseRecipeId(selection);
                if (baseRecipeId == null) continue;
                if (usedBaseRecipeIds.contains(baseRecipeId)) continue;

                usedBaseRecipeIds.add(baseRecipeId);
                result.put(slotIterator.next(), selection);
            }
        }

        return result;
    }

    private static LocalDate normalizeToMonday(LocalDate date) {
        if (date == null) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }
        int diff = date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return date.minusDays(diff);
    }

    private static List<MealPlan.SlotKey> toSlotKeys(List<SlotRequest> requests) {
        if (requests == null) {
            return List.of();
        }

        List<MealPlan.SlotKey> keys = new ArrayList<>();
        for (SlotRequest r : requests) {
            if (r == null || r.mealType() == null || r.dayOfWeek() == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }
            keys.add(new MealPlan.SlotKey(r.mealType(), r.dayOfWeek()));
        }
        return keys;
    }

    private static List<MealPlan.SlotKey> distinctPreserveOrder(List<MealPlan.SlotKey> slots) {
        if (slots == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(slots));
    }

    /**
     * 식단 수정 API
     */
    @Transactional
    public UpdateMealPlanResDTO updateMealPlan(
            Long memberId,
            Long familyRoomId,
            Long mealPlanId,
            UpdateMealPlanReqDTO req
    ) {
        // 방장 검증
        familyRoomService.validateLeader(memberId, familyRoomId);

        MealPlan mealPlan = mealPlanRepository.findById(mealPlanId)
                .orElseThrow(() -> new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_FOUND));

        // 가족방 일치 검증
        if (!mealPlan.getFamilyRoom().getId().equals(familyRoomId)) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_IN_FAMILY_ROOM);
        }

        // 상태 검증: DRAFT만 수정 가능
        if (mealPlan.getStatus() != MealPlanStatus.DRAFT) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_DRAFT);
        }

        // 다중 수정
        List<UpdateMealPlanReqDTO.UpdateItem> items = req.updates();
        if (items == null || items.isEmpty()) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }

        // 유효성 + 중복 슬롯 방지
        Set<MealPlan.SlotKey> seen = new HashSet<>();
        List<UpdateMealPlanResDTO.UpdatedSlot> updated = new ArrayList<>();

        for (UpdateMealPlanReqDTO.UpdateItem item : items) {
            if (item == null || item.selectedSlot() == null || item.selectedRecipe() == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            // slot 파싱
            MealPlan.SlotKey slotKey = new MealPlan.SlotKey(
                    item.selectedSlot().mealType(),
                    item.selectedSlot().dayOfWeek()
            );

            // 요청 내 동일 슬롯 중복 방지
            if (!seen.add(slotKey)) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            // 선택 슬롯인지 검증
            if (!mealPlan.isSelectedSlot(slotKey)) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_SLOT_NOT_SELECTED);
            }

            UpdateMealPlanReqDTO.RecipeRefDTO selection = item.selectedRecipe();
            if (selection.id() == null || selection.type() == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            // 선택 타입에 따라 저장할 id 결정 + title 결정
            Long chosenId;
            String title;

            switch (selection.type()) {
                case RECIPE -> {
                    Recipe recipe = recipeRepository.findById(selection.id())
                            .orElseThrow(() ->
                                    new MealPlanException(MealPlanErrorCode.MEAL_PLAN_RECIPE_NOT_FOUND)
                            );
                    chosenId = recipe.getId();
                    title = recipe.getTitle();
                }

                case TRANSFORMED_RECIPE -> {
                    TransformedRecipe tr = transformedRecipeRepository.findById(selection.id())
                            .orElseThrow(() ->
                                    new MealPlanException(MealPlanErrorCode.MEAL_PLAN_TRANSFORMED_RECIPE_NOT_FOUND)
                            );
                    chosenId = tr.getId();
                    title = tr.getTitle() == null ? "UNKNOWN" : tr.getTitle();
                }

                default -> throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            // 실제 슬롯 컬럼 업데이트 (slot type + id)
            mealPlan.updateSlot(slotKey, toSlotRefType(selection.type()), chosenId);

            RecipeDTO.RecipeType dtoType = (selection.type() == UpdateMealPlanReqDTO.RecipeRefDTO.RecipeType.RECIPE)
                    ? RecipeDTO.RecipeType.RECIPE
                    : RecipeDTO.RecipeType.TRANSFORMED_RECIPE;
            String updatedSlotKeyStr = slotKey.mealType().name() + "-" + slotKey.dayOfWeek().name();
            updated.add(new UpdateMealPlanResDTO.UpdatedSlot(
                    updatedSlotKeyStr,
                    new RecipeDTO(dtoType, chosenId, title)
            ));
        }

        mealPlanRepository.save(mealPlan);

        return UpdateMealPlanResDTO.bulk(mealPlan.getId(), mealPlan.getStatus(), updated);
    }

    /**
     * 식단 확정 API
     * - DRAFT 상태의 식단을 CONFIRMED로 전환
     * - 확정 시 가족방 식단 생성 횟수 +1
     */
    @Transactional
    public ConfirmMealPlanResDTO confirmMealPlan(
            Long memberId,
            Long familyRoomId,
            Long mealPlanId
    ) {
        // 방장 검증
        familyRoomService.validateLeader(memberId, familyRoomId);

        // 잠금 조회
        MealPlan mealPlan = mealPlanRepository.findByIdForConfirm(mealPlanId)
                .orElseThrow(() -> new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_FOUND));

        // 가족방 검증
        if (!mealPlan.getFamilyRoom().getId().equals(familyRoomId)) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_IN_FAMILY_ROOM);
        }

        // 이미 확정된 경우 → 두 번째 요청은 여기서 걸림
        if (mealPlan.getStatus() == MealPlanStatus.CONFIRMED) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_ALREADY_CONFIRMED);
        }

        if (mealPlan.getStatus() != MealPlanStatus.DRAFT) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_NOT_DRAFT);
        }

        // 슬롯 검증
        validateAllSelectedSlotsFilled(mealPlan);

        // 상태 변경 + 카운트 증가
        mealPlan.updateStatus(MealPlanStatus.CONFIRMED);
        mealPlan.getFamilyRoom().incrementMealPlanGenerationCount();

        // 식단 확정 이벤트 발행 (Kafka)
        kafkaEventProducer.sendNotification(
                familyRoomId,
                mealPlan.getFamilyRoom().getMealPlanGenerationCount()
        );

        return new ConfirmMealPlanResDTO(
                mealPlan.getId(),
                mealPlan.getStatus(),
                mealPlan.getWeekStartDate(),
                mealPlan.getFamilyRoom().getMealPlanGenerationCount()
        );
    }

    private void validateAllSelectedSlotsFilled(MealPlan mealPlan) {
        Collection<MealPlan.SlotKey> selectedSlots = mealPlan.getSelectedSlots();

        if (selectedSlots == null || selectedSlots.isEmpty()) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }

        for (MealPlan.SlotKey key : selectedSlots) {
            if (key == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }

            Long value = mealPlan.getSlotValue(key);
            if (value == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }
            // type column must exist as well
            if (mealPlan.getSlotType(key) == null) {
                throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
            }
        }
    }

    private Long resolveBaseRecipeId(RecipeSelectionDTO selection) {
        if (selection == null || selection.type() == null || selection.id() == null) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }

        // Prefer DTO-carrying baseRecipeId to avoid redundant DB lookups
        if (selection.baseRecipeId() != null) {
            return selection.baseRecipeId();
        }

        // Fallback only when baseRecipeId is not provided
        return switch (selection.type()) {
            case RECIPE -> selection.id();
            case TRANSFORMED_RECIPE -> transformedRecipeRepository.findById(selection.id())
                    .filter(tr -> tr.getBaseRecipe() != null)
                    .map(tr -> tr.getBaseRecipe().getId())
                    .orElseThrow(() ->
                            new MealPlanException(MealPlanErrorCode.MEAL_PLAN_TRANSFORMED_RECIPE_NOT_FOUND)
                    );
        };
    }

    private static MealPlan.SlotRefType toSlotRefType(RecipeSelectionDTO.RecipeSelectionType type) {
        if (type == null) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }
        return switch (type) {
            case RECIPE -> MealPlan.SlotRefType.RECIPE;
            case TRANSFORMED_RECIPE -> MealPlan.SlotRefType.TRANSFORMED_RECIPE;
        };
    }

    private static MealPlan.SlotRefType toSlotRefType(UpdateMealPlanReqDTO.RecipeRefDTO.RecipeType type) {
        if (type == null) {
            throw new MealPlanException(MealPlanErrorCode.MEAL_PLAN_VALIDATION_FAILED);
        }
        return switch (type) {
            case RECIPE -> MealPlan.SlotRefType.RECIPE;
            case TRANSFORMED_RECIPE -> MealPlan.SlotRefType.TRANSFORMED_RECIPE;
        };
    }
}

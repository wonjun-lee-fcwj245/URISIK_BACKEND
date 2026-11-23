package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.converter.RecipeSearchConverter;
import com.urisik.backend.domain.recipe.converter.RecipeTextParser;
import com.urisik.backend.domain.recipe.document.RecipeDocument;
import com.urisik.backend.domain.recipe.dto.res.RecipeSearchResponseDTO;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.FoodSafetyRecipeClient;
import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.dto.FoodSafetyRecipeResponse;
import com.urisik.backend.domain.recipe.repository.RecipeExternalMetadataRepository;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.search.service.SearchLogService;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.global.util.QwertyToKoreanConverter;

import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeSearchService {

    private final RecipeRepository recipeRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final FoodSafetyRecipeClient foodSafetyRecipeClient;
    private final AllergyRiskService allergyRiskService;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final SearchLogService searchLogService;
    private final RecipeElasticSearchService recipeElasticSearchService;
    private final MeterRegistry meterRegistry;
    private final PlatformTransactionManager transactionManager;

    @Value("${search.engine:elasticsearch}")
    private String searchEngine;

    /**
     * ES 동시 호출 한도. 검색 트랜잭션을 줄이기 전에는 ES 호출이 DB 트랜잭션 안에 있어 DB 커넥션 풀 크기가
     * 동시 호출 수를 우연히 제한했다. 트랜잭션 밖으로 나오면서 그 제한이 사라지므로 명시적으로 둔다(#24).
     * 서킷브레이커 바깥에서 대기하므로 대기 시간은 서킷의 느린 호출로 집계되지 않는다.
     */
    @Value("${search.es.max-concurrency:10}")
    private int esMaxConcurrency = 10;

    /** 한도를 얻기 위해 기다리는 최대 시간(ms). 넘으면 기존 MySQL 대체 경로로 보낸다. */
    @Value("${search.es.queue-wait-ms:2000}")
    private long esQueueWaitMs = 2000;

    private volatile Semaphore esSemaphore;

    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "TRANSFORMED", 0,
            "RECIPE", 1,
            "EXTERNAL", 2
    );

    /** FULLTEXT BOOLEAN MODE 특수문자 패턴 (#5) */
    private static final Pattern BOOLEAN_OPERATORS = Pattern.compile("[+\\-*()~<>\"@]");

    /**
     * 검색. 메서드 전체를 하나의 트랜잭션으로 묶지 않는다.
     * 트랜잭션을 길게 잡으면 첫 SQL부터 커밋까지 DB 커넥션을 쥔 채 ES·외부 API 응답을 기다려
     * 호출이 몰릴 때 커넥션 풀이 포화한다(#24). DB를 읽는 구간(프로필·알레르기, 엔티티 조회와 변환)만
     * 짧은 읽기 트랜잭션으로 감싸고, ES 조회와 외부 API 호출은 트랜잭션 밖에서 실행한다.
     */
    public RecipeSearchResponseDTO search(Long loginUserId, String keyword, int page, int size) {

        String originalKeyword = keyword;

        // 영문 자판 입력 감지 및 한글 변환 (예: "rlaclWlro" → "김치찌개")
        if (QwertyToKoreanConverter.isEnglishKeyboard(keyword)) {
            String converted = QwertyToKoreanConverter.convert(keyword);
            log.info("영문 자판 입력 감지: '{}' → '{}'", keyword, converted);
            keyword = converted;
        }

        searchLogService.logSearch(loginUserId, keyword);

        List<Allergen> familyAllergens = inReadOnlyTx(() -> {
            FamilyMemberProfile profile =
                    familyMemberProfileRepository.findByMember_Id(loginUserId)
                            .orElseThrow(() ->
                                    new GeneralException(GeneralErrorCode.NOT_FOUND));

            Long familyRoomId = profile.getFamilyRoom().getId();
            return allergyRiskService.getFamilyAllergens(familyRoomId);
        });

        PageRequest pageable = PageRequest.of(page, size);
        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>(
                searchInternal(keyword, pageable, familyAllergens));

        // (#6) 영문 변환 후 결과가 0건이면 원본 키워드로 재검색
        if (items.isEmpty() && !originalKeyword.equals(keyword)) {
            log.info("변환된 키워드 '{}' 결과 0건, 원본 '{}' 로 재검색", keyword, originalKeyword);
            keyword = originalKeyword;
            items.addAll(searchInternal(keyword, pageable, familyAllergens));
        }

        // 외부 API 검색 (기존 유지). 트랜잭션 밖이라 응답을 기다리는 동안 DB 커넥션을 쥐지 않는다.
        int startIdx = page * size + 1;
        int endIdx = startIdx + size - 1;

        List<FoodSafetyRecipeResponse.Row> externals = List.of();
        try {
            externals = foodSafetyRecipeClient.searchByName(keyword, startIdx, endIdx);
        } catch (Exception e) {
            log.warn("외부 API 검색 실패, 내부 결과만 반환: {}", e.getMessage());
        }

        for (FoodSafetyRecipeResponse.Row row : externals) {
            items.add(RecipeSearchConverter.fromExternal(row));
        }

        // 정렬
        items.sort(reviewSortComparator());

        return new RecipeSearchResponseDTO(items);
    }

    /** search.engine 설정에 따라 내부(DB·ES) 검색을 수행한다. DB를 읽는 구간만 읽기 트랜잭션이다. */
    private List<RecipeSearchResponseDTO.Item> searchInternal(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        if ("mysql".equals(searchEngine)) {
            return inReadOnlyTx(() -> searchFromMySQL(keyword, pageable, familyAllergens));
        }
        if ("fulltext".equals(searchEngine)) {
            return inReadOnlyTx(() -> searchFromFulltext(keyword, pageable, familyAllergens));
        }
        // elasticsearch (기본) → ES 시도 + MySQL fallback
        try {
            return searchFromElasticsearch(keyword, pageable, familyAllergens);
        } catch (Exception e) {
            log.warn("ES 검색 실패, MySQL fallback 사용: {}", e.getMessage());
            return inReadOnlyTx(() -> searchFromMySQL(keyword, pageable, familyAllergens));
        }
    }

    private <T> T inReadOnlyTx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> work.get());
    }

    /**
     * Elasticsearch 기반 검색
     */
    private List<RecipeSearchResponseDTO.Item> searchFromElasticsearch(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        // ES 조회는 네트워크 호출이라 트랜잭션 밖에서 실행하되, 동시 호출 수는 제한한다
        List<RecipeDocument> esResults = searchWithConcurrencyLimit(keyword, pageable);

        if (esResults.isEmpty()) {
            return List.of();
        }

        return inReadOnlyTx(() -> itemsFromDocuments(esResults, familyAllergens));
    }

    private Semaphore esSemaphore() {
        Semaphore sem = esSemaphore;
        if (sem == null) {
            synchronized (this) {
                if (esSemaphore == null) {
                    esSemaphore = new Semaphore(Math.max(1, esMaxConcurrency), true);
                }
                sem = esSemaphore;
            }
        }
        return sem;
    }

    /** ES 동시 호출 수를 제한한다. 한도를 얻지 못하면 예외를 던져 호출부가 MySQL 대체 경로로 전환하게 한다. */
    private List<RecipeDocument> searchWithConcurrencyLimit(String keyword, PageRequest pageable) {
        Semaphore sem = esSemaphore();
        boolean acquired;
        try {
            acquired = sem.tryAcquire(esQueueWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ES 동시 호출 한도를 기다리다 중단됨", e);
        }
        if (!acquired) {
            meterRegistry.counter("search.es.concurrency.rejected").increment();
            throw new IllegalStateException("ES 동시 호출 한도 초과(" + esMaxConcurrency + ", 대기 " + esQueueWaitMs + "ms)");
        }
        try {
            return recipeElasticSearchService.search(keyword, pageable);
        } finally {
            sem.release();
        }
    }

    /** ES 결과 문서를 엔티티로 조회해 응답 항목으로 변환한다(DB 조회 구간, 읽기 트랜잭션 안에서 호출). */
    private List<RecipeSearchResponseDTO.Item> itemsFromDocuments(
            List<RecipeDocument> esResults, List<Allergen> familyAllergens) {

        List<Long> recipeIds = new ArrayList<>();
        List<Long> transformedIds = new ArrayList<>();

        for (RecipeDocument doc : esResults) {
            if ("RECIPE".equals(doc.getType())) {
                recipeIds.add(doc.getRecipeId());
            } else if ("TRANSFORMED".equals(doc.getType())) {
                transformedIds.add(doc.getRecipeId());
            }
        }

        // Recipe: EntityGraph로 metadata JOIN FETCH (N+1 방지)
        Map<Long, Recipe> recipeMap = recipeIds.isEmpty()
                ? Map.of()
                : recipeRepository.findAllByIdWithMetadata(recipeIds).stream()
                        .collect(Collectors.toMap(Recipe::getId, r -> r));

        Map<Long, TransformedRecipe> transformedMap = transformedIds.isEmpty()
                ? Map.of()
                : transformedRecipeRepository.findAllById(transformedIds).stream()
                        .collect(Collectors.toMap(TransformedRecipe::getId, tr -> tr));

        // TransformedRecipe의 baseRecipe metadata가 필요한 경우 별도 조회
        Map<Long, RecipeExternalMetadata> trMetadataMap;
        if (!transformedMap.isEmpty()) {
            List<Long> baseRecipeIds = transformedMap.values().stream()
                    .map(tr -> tr.getBaseRecipe().getId())
                    .toList();
            trMetadataMap = metadataRepository.findByRecipe_IdIn(baseRecipeIds).stream()
                    .collect(Collectors.toMap(
                            m -> m.getRecipe().getId(),
                            m -> m,
                            (a, b) -> a
                    ));
        } else {
            trMetadataMap = Map.of();
        }

        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        for (RecipeDocument doc : esResults) {
            if ("RECIPE".equals(doc.getType())) {
                Recipe recipe = recipeMap.get(doc.getRecipeId());
                if (recipe == null) continue;

                RecipeExternalMetadata meta = recipe.getRecipeExternalMetadata();
                Boolean safe = determineSafety(
                        familyAllergens,
                        RecipeTextParser.parseIngredients(recipe.getIngredientsRaw())
                );
                items.add(RecipeSearchConverter.fromRecipe(recipe, meta, safe));

            } else if ("TRANSFORMED".equals(doc.getType())) {
                TransformedRecipe tr = transformedMap.get(doc.getRecipeId());
                if (tr == null) continue;

                RecipeExternalMetadata meta = trMetadataMap.get(tr.getBaseRecipe().getId());
                Boolean safe = determineSafety(
                        familyAllergens,
                        RecipeTextParser.parseIngredients(tr.getIngredientsRaw())
                );
                items.add(RecipeSearchConverter.fromTransformed(tr, meta, safe));
            }
        }

        return items;
    }

    /**
     * MySQL LIKE 기반 검색 (ES 장애 시 fallback 또는 search.engine=mysql)
     * (#7) 타이머는 DB 쿼리만 감싼다.
     */
    private List<RecipeSearchResponseDTO.Item> searchFromMySQL(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        List<Recipe> recipes = meterRegistry.timer("search.mysql.query")
                .record(() -> recipeRepository.findByTitleContainingIgnoreCase(keyword, pageable));
        List<TransformedRecipe> trs = transformedRecipeRepository.findByTitleLike(keyword, pageable);

        List<Long> recipeIds = new ArrayList<>();
        recipes.forEach(r -> recipeIds.add(r.getId()));
        trs.forEach(tr -> recipeIds.add(tr.getBaseRecipe().getId()));

        Map<Long, RecipeExternalMetadata> metadataMap = recipeIds.isEmpty()
                ? Map.of()
                : metadataRepository.findByRecipe_IdIn(recipeIds).stream()
                        .collect(Collectors.toMap(
                                m -> m.getRecipe().getId(),
                                m -> m,
                                (a, b) -> a
                        ));

        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        for (Recipe r : recipes) {
            RecipeExternalMetadata meta = metadataMap.get(r.getId());
            Boolean safe = determineSafety(
                    familyAllergens,
                    RecipeTextParser.parseIngredients(r.getIngredientsRaw())
            );
            items.add(RecipeSearchConverter.fromRecipe(r, meta, safe));
        }

        for (TransformedRecipe tr : trs) {
            RecipeExternalMetadata meta = metadataMap.get(tr.getBaseRecipe().getId());
            Boolean safe = determineSafety(
                    familyAllergens,
                    RecipeTextParser.parseIngredients(tr.getIngredientsRaw())
            );
            items.add(RecipeSearchConverter.fromTransformed(tr, meta, safe));
        }

        return items;
    }

    /**
     * MySQL FULLTEXT(ngram) 기반 검색 (search.engine=fulltext)
     * (#5) Boolean 특수문자 strip, (#7) 타이머는 DB 쿼리만, (#8) TransformedRecipe 포함, (#8-1) 1글자 LIKE fallback
     */
    private List<RecipeSearchResponseDTO.Item> searchFromFulltext(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        // (#5) Boolean 연산자 제거
        String sanitized = BOOLEAN_OPERATORS.matcher(keyword).replaceAll("");
        if (sanitized.isBlank()) {
            return List.of();
        }

        // (#8-1) 1글자 검색어는 ngram_token_size=2에서 토큰화 불가 → LIKE fallback
        List<Recipe> recipes;
        if (sanitized.length() == 1) {
            recipes = meterRegistry.timer("search.fulltext.query")
                    .record(() -> recipeRepository.findByTitleContainingIgnoreCase(sanitized, pageable));
        } else {
            recipes = meterRegistry.timer("search.fulltext.query")
                    .record(() -> recipeRepository.findByTitleFulltext(sanitized, pageable));
        }

        // (#8) TransformedRecipe도 검색 (FULLTEXT 인덱스 없으므로 LIKE 사용)
        List<TransformedRecipe> trs = transformedRecipeRepository.findByTitleLike(sanitized, pageable);

        List<Long> recipeIds = new ArrayList<>();
        recipes.forEach(r -> recipeIds.add(r.getId()));
        trs.forEach(tr -> recipeIds.add(tr.getBaseRecipe().getId()));

        Map<Long, RecipeExternalMetadata> metadataMap = recipeIds.isEmpty()
                ? Map.of()
                : metadataRepository.findByRecipe_IdIn(recipeIds).stream()
                        .collect(Collectors.toMap(
                                m -> m.getRecipe().getId(),
                                m -> m,
                                (a, b) -> a
                        ));

        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        for (Recipe r : recipes) {
            RecipeExternalMetadata meta = metadataMap.get(r.getId());
            Boolean safe = determineSafety(
                    familyAllergens,
                    RecipeTextParser.parseIngredients(r.getIngredientsRaw())
            );
            items.add(RecipeSearchConverter.fromRecipe(r, meta, safe));
        }

        for (TransformedRecipe tr : trs) {
            RecipeExternalMetadata meta = metadataMap.get(tr.getBaseRecipe().getId());
            Boolean safe = determineSafety(
                    familyAllergens,
                    RecipeTextParser.parseIngredients(tr.getIngredientsRaw())
            );
            items.add(RecipeSearchConverter.fromTransformed(tr, meta, safe));
        }

        return items;
    }

    private Boolean determineSafety(
            List<Allergen> familyAllergens,
            List<String> ingredients
    ) {
        if (familyAllergens == null || familyAllergens.isEmpty()) {
            return null;
        }

        return allergyRiskService
                .detectRiskAllergens(familyAllergens, ingredients)
                .isEmpty();
    }

    private Comparator<RecipeSearchResponseDTO.Item> reviewSortComparator() {
        return Comparator
                .comparing(
                        RecipeSearchResponseDTO.Item::getAvgScore,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(
                        RecipeSearchResponseDTO.Item::getReviewCount,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(item -> TYPE_PRIORITY.getOrDefault(item.getType(), 99))
                .thenComparing(RecipeSearchResponseDTO.Item::getTitle);
    }
}

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urisik.backend.domain.allergy.enums.Allergen;

import java.util.*;
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

    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "TRANSFORMED", 0,
            "RECIPE", 1,
            "EXTERNAL", 2
    );

    @Transactional(readOnly = true)
    public RecipeSearchResponseDTO search(Long loginUserId, String keyword, int page, int size) {

        searchLogService.logSearch(loginUserId, keyword);

        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() ->
                                new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();

        PageRequest pageable = PageRequest.of(page, size);
        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        // 알레르기 1회 사전 조회
        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);

        // 1) ES 검색 시도, 실패 시 MySQL fallback
        try {
            items.addAll(searchFromElasticsearch(keyword, pageable, familyAllergens));
        } catch (Exception e) {
            log.warn("ES 검색 실패, MySQL fallback 사용: {}", e.getMessage());
            items.addAll(searchFromMySQL(keyword, pageable, familyAllergens));
        }

        // 2) 외부 API 검색 (기존 유지)
        int startIdx = page * size + 1;
        int endIdx = startIdx + size - 1;

        List<FoodSafetyRecipeResponse.Row> externals =
                foodSafetyRecipeClient.searchByName(keyword, startIdx, endIdx);

        for (FoodSafetyRecipeResponse.Row row : externals) {
            items.add(RecipeSearchConverter.fromExternal(row));
        }

        // 3) 정렬
        items.sort(reviewSortComparator());

        return new RecipeSearchResponseDTO(items);
    }

    /**
     * Elasticsearch 기반 검색
     * - ES에서 매칭된 recipeId 목록을 받아옴
     * - 해당 ID로 MySQL에서 엔티티 조회 (메타데이터, 알레르기 체크)
     */
    private List<RecipeSearchResponseDTO.Item> searchFromElasticsearch(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        List<RecipeDocument> esResults = recipeElasticSearchService.search(keyword, pageable);

        if (esResults.isEmpty()) {
            return List.of();
        }

        // ES 결과에서 recipeId 추출 (타입별 분류)
        List<Long> recipeIds = new ArrayList<>();
        List<Long> transformedIds = new ArrayList<>();

        for (RecipeDocument doc : esResults) {
            if ("RECIPE".equals(doc.getType())) {
                recipeIds.add(doc.getRecipeId());
            } else if ("TRANSFORMED".equals(doc.getType())) {
                transformedIds.add(doc.getRecipeId());
            }
        }

        // MySQL에서 엔티티 조회
        Map<Long, Recipe> recipeMap = recipeIds.isEmpty()
                ? Map.of()
                : recipeRepository.findAllById(recipeIds).stream()
                        .collect(Collectors.toMap(Recipe::getId, r -> r));

        Map<Long, TransformedRecipe> transformedMap = transformedIds.isEmpty()
                ? Map.of()
                : transformedRecipeRepository.findAllById(transformedIds).stream()
                        .collect(Collectors.toMap(TransformedRecipe::getId, tr -> tr));

        // 메타데이터 배치 로딩 (IN 쿼리 1번)
        List<Long> allBaseRecipeIds = new ArrayList<>(recipeIds);
        transformedMap.values().forEach(tr -> allBaseRecipeIds.add(tr.getBaseRecipe().getId()));

        Map<Long, RecipeExternalMetadata> metadataMap = allBaseRecipeIds.isEmpty()
                ? Map.of()
                : metadataRepository.findByRecipe_IdIn(allBaseRecipeIds).stream()
                        .collect(Collectors.toMap(
                                m -> m.getRecipe().getId(),
                                m -> m,
                                (a, b) -> a
                        ));

        // DTO 변환 + 알레르기 체크 (기존 로직 동일)
        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        for (RecipeDocument doc : esResults) {
            if ("RECIPE".equals(doc.getType())) {
                Recipe recipe = recipeMap.get(doc.getRecipeId());
                if (recipe == null) continue;

                RecipeExternalMetadata meta = metadataMap.get(recipe.getId());
                Boolean safe = determineSafety(
                        familyAllergens,
                        RecipeTextParser.parseIngredients(recipe.getIngredientsRaw())
                );
                items.add(RecipeSearchConverter.fromRecipe(recipe, meta, safe));

            } else if ("TRANSFORMED".equals(doc.getType())) {
                TransformedRecipe tr = transformedMap.get(doc.getRecipeId());
                if (tr == null) continue;

                RecipeExternalMetadata meta = metadataMap.get(tr.getBaseRecipe().getId());
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
     * MySQL LIKE 기반 검색 (ES 장애 시 fallback)
     */
    private List<RecipeSearchResponseDTO.Item> searchFromMySQL(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        List<Recipe> recipes = recipeRepository.findByTitleContainingIgnoreCase(keyword, pageable);
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

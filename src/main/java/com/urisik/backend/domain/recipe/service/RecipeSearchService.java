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
import org.springframework.transaction.annotation.Transactional;

import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.global.util.QwertyToKoreanConverter;

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
    private final MeterRegistry meterRegistry;

    @Value("${search.engine:elasticsearch}")
    private String searchEngine;

    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "TRANSFORMED", 0,
            "RECIPE", 1,
            "EXTERNAL", 2
    );

    @Transactional(readOnly = true)
    public RecipeSearchResponseDTO search(Long loginUserId, String keyword, int page, int size) {

        // 영문 자판 입력 감지 및 한글 변환 (예: "rlaclWlro" → "김치찌개")
        if (QwertyToKoreanConverter.isEnglishKeyboard(keyword)) {
            String converted = QwertyToKoreanConverter.convert(keyword);
            log.debug("영문 자판 입력 감지: '{}' → '{}'", keyword, converted);
            keyword = converted;
        }

        searchLogService.logSearch(loginUserId, keyword);

        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() ->
                                new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();

        PageRequest pageable = PageRequest.of(page, size);
        List<RecipeSearchResponseDTO.Item> items = new ArrayList<>();

        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);

        // search.engine 설정에 따라 분기
        if ("mysql".equals(searchEngine)) {
            items.addAll(searchFromMySQL(keyword, pageable, familyAllergens));
        } else if ("fulltext".equals(searchEngine)) {
            items.addAll(searchFromFulltext(keyword, pageable, familyAllergens));
        } else {
            // elasticsearch (기본) → ES 시도 + MySQL fallback
            try {
                items.addAll(searchFromElasticsearch(keyword, pageable, familyAllergens));
            } catch (Exception e) {
                log.warn("ES 검색 실패, MySQL fallback 사용: {}", e.getMessage());
                items.addAll(searchFromMySQL(keyword, pageable, familyAllergens));
            }
        }

        // 외부 API 검색 (기존 유지)
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

    /**
     * Elasticsearch 기반 검색
     */
    private List<RecipeSearchResponseDTO.Item> searchFromElasticsearch(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        List<RecipeDocument> esResults = recipeElasticSearchService.search(keyword, pageable);

        if (esResults.isEmpty()) {
            return List.of();
        }

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
     */
    private List<RecipeSearchResponseDTO.Item> searchFromMySQL(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        return meterRegistry.timer("search.mysql.query").record(() -> {
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
        });
    }

    /**
     * MySQL FULLTEXT(ngram) 기반 검색 (search.engine=fulltext)
     */
    private List<RecipeSearchResponseDTO.Item> searchFromFulltext(
            String keyword, PageRequest pageable, List<Allergen> familyAllergens) {

        return meterRegistry.timer("search.fulltext.query").record(() -> {
            List<Recipe> recipes = recipeRepository.findByTitleFulltext(keyword, pageable);

            List<Long> recipeIds = recipes.stream().map(Recipe::getId).toList();

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

            return items;
        });
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

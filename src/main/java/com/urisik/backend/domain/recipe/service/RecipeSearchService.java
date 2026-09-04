package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.converter.RecipeSearchConverter;
import com.urisik.backend.domain.recipe.converter.RecipeTextParser;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urisik.backend.domain.allergy.enums.Allergen;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecipeSearchService {

    private final RecipeRepository recipeRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final FoodSafetyRecipeClient foodSafetyRecipeClient;
    private final AllergyRiskService allergyRiskService;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final SearchLogService searchLogService;

    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "TRANSFORMED", 0,
            "RECIPE", 1,
            "EXTERNAL", 2
    );

    @Transactional(readOnly = true)
    public RecipeSearchResponseDTO search(Long loginUserId,String keyword, int page, int size) {

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

        // 1) 내부 원본 레시피
        List<Recipe> recipes = recipeRepository.findByTitleContainingIgnoreCase(keyword, pageable);

        // 2) 공개 변형 레시피
        List<TransformedRecipe> trs =
                transformedRecipeRepository.findByTitleLike(keyword, pageable);

        // 3) 메타데이터 배치 로딩 (IN 쿼리 1번)
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

        // 4) 결과 변환 (DB 조회 없이 Map 참조)
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
                    RecipeTextParser.parseIngredients(
                            tr.getIngredientsRaw()
                    )
            );

            items.add(RecipeSearchConverter.fromTransformed(tr, meta, safe));
        }

        // 3) 외부 API 검색 ( row를 상세 저장에 쓸 snapshot으로도 내려줌)
        int startIdx = page * size + 1;
        int endIdx = startIdx + size - 1;

        List<FoodSafetyRecipeResponse.Row> externals =
                foodSafetyRecipeClient.searchByName(keyword, startIdx, endIdx);

        for (FoodSafetyRecipeResponse.Row row : externals) {
            items.add(RecipeSearchConverter.fromExternal(row));
        }

        // 4) 리뷰 높은 순 정렬
        items.sort(reviewSortComparator());

        return new RecipeSearchResponseDTO(items);
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



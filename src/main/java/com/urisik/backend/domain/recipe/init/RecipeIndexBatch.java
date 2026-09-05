package com.urisik.backend.domain.recipe.init;

import com.urisik.backend.domain.recipe.document.RecipeDocument;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.repository.RecipeExternalMetadataRepository;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.recipe.service.RecipeElasticSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecipeIndexBatch {

    private final RecipeRepository recipeRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final RecipeElasticSearchService recipeElasticSearchService;

    @EventListener(ApplicationReadyEvent.class)
    public void indexAllRecipes() {
        try {
            log.info("ES 초기 인덱싱 시작");

            // 1) 모든 Recipe 조회
            List<Recipe> recipes = recipeRepository.findAll();

            // 2) 메타데이터 배치 로딩
            List<Long> recipeIds = recipes.stream().map(Recipe::getId).toList();
            Map<Long, RecipeExternalMetadata> metadataMap = metadataRepository
                    .findByRecipe_IdIn(recipeIds).stream()
                    .collect(Collectors.toMap(
                            m -> m.getRecipe().getId(),
                            m -> m,
                            (a, b) -> a
                    ));

            // 3) Recipe → RecipeDocument 변환
            List<RecipeDocument> documents = new ArrayList<>();
            for (Recipe recipe : recipes) {
                RecipeExternalMetadata meta = metadataMap.get(recipe.getId());
                documents.add(RecipeDocument.from(recipe, meta));
            }

            // 4) 모든 TransformedRecipe 조회 + 변환
            List<TransformedRecipe> transformedRecipes = transformedRecipeRepository.findAll();
            for (TransformedRecipe tr : transformedRecipes) {
                RecipeExternalMetadata meta = metadataMap.get(tr.getBaseRecipe().getId());
                documents.add(RecipeDocument.from(tr, meta));
            }

            // 5) bulk 인덱싱
            recipeElasticSearchService.indexAll(documents);

            log.info("ES 초기 인덱싱 완료: Recipe {}건, TransformedRecipe {}건",
                    recipes.size(), transformedRecipes.size());

        } catch (Exception e) {
            log.warn("ES 초기 인덱싱 실패 (애플리케이션은 정상 동작): {}", e.getMessage());
        }
    }
}

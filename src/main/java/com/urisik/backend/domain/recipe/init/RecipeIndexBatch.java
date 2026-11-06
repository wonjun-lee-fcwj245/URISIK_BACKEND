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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasData;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 앱 시작 시 ES 인덱스를 재구축한다.
 * alias 기반 무중단 전환: 새 인덱스 완성 → alias 전환 → 구 인덱스 삭제.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecipeIndexBatch {

    private static final String ALIAS_NAME = "recipes";

    private final RecipeRepository recipeRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final RecipeElasticSearchService recipeElasticSearchService;
    private final ElasticsearchOperations elasticsearchOperations;

    @EventListener(ApplicationReadyEvent.class)
    public void indexAllRecipes() {
        try {
            log.info("ES 초기 인덱싱 시작 (alias 전환 방식)");

            String newIndexName = ALIAS_NAME + "_v" + Instant.now().toEpochMilli();
            IndexCoordinates newIndex = IndexCoordinates.of(newIndexName);

            // 1) 새 인덱스 생성 (settings + mapping)
            IndexOperations newIndexOps = elasticsearchOperations.indexOps(newIndex);
            IndexOperations templateOps = elasticsearchOperations.indexOps(RecipeDocument.class);

            newIndexOps.create(templateOps.createSettings());
            newIndexOps.putMapping(templateOps.createMapping());
            log.info("새 ES 인덱스 생성: {}", newIndexName);

            // 2) 모든 Recipe 조회 + 메타데이터 배치 로딩
            List<Recipe> recipes = recipeRepository.findAll();
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

            // 4) TransformedRecipe 변환
            List<TransformedRecipe> transformedRecipes = transformedRecipeRepository.findAll();
            for (TransformedRecipe tr : transformedRecipes) {
                RecipeExternalMetadata meta = metadataMap.get(tr.getBaseRecipe().getId());
                documents.add(RecipeDocument.from(tr, meta));
            }

            // 5) 새 인덱스에 bulk 인덱싱 (500건씩)
            int batchSize = 500;
            for (int i = 0; i < documents.size(); i += batchSize) {
                List<RecipeDocument> batch = documents.subList(i, Math.min(i + batchSize, documents.size()));
                elasticsearchOperations.save(batch, newIndex);
            }
            log.info("새 인덱스 인덱싱 완료: {}건 (Recipe {}, Transformed {})",
                    documents.size(), recipes.size(), transformedRecipes.size());

            // 6) alias 전환: 기존 인덱스에서 alias 제거 + 새 인덱스에 alias 추가
            Set<String> oldIndices = Set.of();
            try {
                // alias가 존재하면 연결된 인덱스 목록 획득
                Map<String, Set<AliasData>> aliasInfo = newIndexOps.getAliases(ALIAS_NAME);
                if (aliasInfo != null) {
                    oldIndices = aliasInfo.keySet();
                }
            } catch (Exception e) {
                // alias가 아니라 실제 인덱스 "recipes"가 존재하는 경우
                IndexOperations legacyOps = elasticsearchOperations.indexOps(IndexCoordinates.of(ALIAS_NAME));
                if (legacyOps.exists()) {
                    legacyOps.delete();
                    log.info("레거시 인덱스 '{}' 삭제 (alias로 전환)", ALIAS_NAME);
                }
            }

            // 새 인덱스에 alias 추가
            AliasActionParameters addParams = AliasActionParameters.builder()
                    .withIndices(newIndexName)
                    .withAliases(ALIAS_NAME)
                    .build();
            newIndexOps.alias(new AliasActions(new AliasAction.Add(addParams)));
            log.info("alias '{}' → '{}' 연결", ALIAS_NAME, newIndexName);

            // 구 인덱스에서 alias 제거 + 삭제
            for (String oldIdx : oldIndices) {
                if (!oldIdx.equals(newIndexName)) {
                    try {
                        IndexOperations oldOps = elasticsearchOperations.indexOps(IndexCoordinates.of(oldIdx));
                        AliasActionParameters removeParams = AliasActionParameters.builder()
                                .withIndices(oldIdx)
                                .withAliases(ALIAS_NAME)
                                .build();
                        oldOps.alias(new AliasActions(new AliasAction.Remove(removeParams)));
                        oldOps.delete();
                        log.info("구 인덱스 삭제: {}", oldIdx);
                    } catch (Exception e) {
                        log.warn("구 인덱스 정리 실패 ({}): {}", oldIdx, e.getMessage());
                    }
                }
            }

            log.info("ES 초기 인덱싱 완료 (무중단 alias 전환)");

        } catch (Exception e) {
            log.warn("ES 초기 인덱싱 실패 (애플리케이션은 정상 동작): {}", e.getMessage());
        }
    }
}

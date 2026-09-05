package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.recipe.document.RecipeDocument;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.repository.RecipeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeElasticSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final RecipeDocumentRepository recipeDocumentRepository;

    /**
     * ES multi_match 검색 (title^2 + ingredientsRaw, fuzziness 오타 보정)
     */
    public List<RecipeDocument> search(String keyword, Pageable pageable) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(keyword)
                        .fields("title^2", "ingredientsRaw")
                        .fuzziness("AUTO")
                ))
                .withPageable(pageable)
                .build();

        SearchHits<RecipeDocument> hits = elasticsearchOperations.search(query, RecipeDocument.class);

        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();
    }

    /**
     * Recipe → ES 인덱싱
     */
    public void indexRecipe(Recipe recipe, RecipeExternalMetadata metadata) {
        try {
            RecipeDocument doc = RecipeDocument.from(recipe, metadata);
            recipeDocumentRepository.save(doc);
        } catch (Exception e) {
            log.warn("ES 인덱싱 실패 (Recipe id={}): {}", recipe.getId(), e.getMessage());
        }
    }

    /**
     * TransformedRecipe → ES 인덱싱
     */
    public void indexTransformedRecipe(TransformedRecipe tr, RecipeExternalMetadata metadata) {
        try {
            RecipeDocument doc = RecipeDocument.from(tr, metadata);
            recipeDocumentRepository.save(doc);
        } catch (Exception e) {
            log.warn("ES 인덱싱 실패 (TransformedRecipe id={}): {}", tr.getId(), e.getMessage());
        }
    }

    /**
     * ES 문서 삭제
     */
    public void deleteDocument(String documentId) {
        try {
            recipeDocumentRepository.deleteById(documentId);
        } catch (Exception e) {
            log.warn("ES 문서 삭제 실패 (id={}): {}", documentId, e.getMessage());
        }
    }

    /**
     * bulk 인덱싱
     */
    public void indexAll(Iterable<RecipeDocument> documents) {
        recipeDocumentRepository.saveAll(documents);
    }
}

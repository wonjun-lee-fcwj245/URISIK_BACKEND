package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.recipe.document.RecipeDocument;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.repository.RecipeDocumentRepository;
import com.urisik.backend.global.util.JamoUtils;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;

    /**
     * ES 검색: nori multi_match + 자모 fuzziness 오타 보정.
     *
     * <p>bool should로 두 쿼리를 조합한다.</p>
     * <ul>
     *   <li>should[0]: nori 분석 multi_match (title^2, ingredientsRaw) — 형태소 매칭</li>
     *   <li>should[1]: jamo 분석 match (titleJamo^3, fuzziness AUTO) — 자모 레벨 오타 보정</li>
     * </ul>
     *
     * <p>Resilience4j 서킷 브레이커를 적용하여 ES 장애에 대응한다.
     * 검색은 사용자 대기가 걸리므로 재시도 없이 즉시 fallback 전환한다.</p>
     */
    @CircuitBreaker(name = "elasticsearchApi", fallbackMethod = "searchFallback")
    public List<RecipeDocument> search(String keyword, Pageable pageable) {
        String keywordJamo = JamoUtils.decompose(keyword);

        return meterRegistry.timer("search.es.query").record(() -> {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .should(s -> s.multiMatch(m -> m
                                    .query(keyword)
                                    .fields("title^2", "ingredientsRaw")
                            ))
                            .should(s -> s.match(m -> m
                                    .field("titleJamo")
                                    .query(keywordJamo)
                                    .fuzziness("AUTO")
                                    .boost(3.0f)
                            ))
                    ))
                    .withPageable(pageable)
                    .build();

            SearchHits<RecipeDocument> hits = elasticsearchOperations.search(query, RecipeDocument.class);

            return hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .toList();
        });
    }

    /**
     * ES 검색 실패 시 fallback. 서킷 OPEN 또는 재시도 소진 시 호출된다.
     * RuntimeException을 던져 RecipeSearchService에서 catch → MySQL fallback 전환.
     */
    private List<RecipeDocument> searchFallback(String keyword, Pageable pageable, Throwable t) {
        log.warn("ES CircuitBreaker fallback: {}", t.getMessage());
        throw new RuntimeException("ES 검색 불가, MySQL fallback 전환", t);
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

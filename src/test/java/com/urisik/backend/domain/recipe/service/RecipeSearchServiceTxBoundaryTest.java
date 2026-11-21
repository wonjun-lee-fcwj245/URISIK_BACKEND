package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.document.RecipeDocument;
import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.FoodSafetyRecipeClient;
import com.urisik.backend.domain.recipe.repository.RecipeExternalMetadataRepository;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.search.service.SearchLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 검색에서 외부 API·ES 호출은 트랜잭션(DB 커넥션 점유) 밖에서, DB 조회는 트랜잭션 안에서 실행되는지 고정한다(#24). */
class RecipeSearchServiceTxBoundaryTest {

    /** 실제 DB 없이 "트랜잭션이 활성인 구간"만 만들어 주는 트랜잭션 매니저 */
    private static class MarkerTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    private static boolean inTx() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final TransformedRecipeRepository transformedRecipeRepository = mock(TransformedRecipeRepository.class);
    private final FamilyMemberProfileRepository profileRepository = mock(FamilyMemberProfileRepository.class);
    private final FoodSafetyRecipeClient externalClient = mock(FoodSafetyRecipeClient.class);
    private final RecipeElasticSearchService elasticSearchService = mock(RecipeElasticSearchService.class);
    private final AllergyRiskService allergyRiskService = mock(AllergyRiskService.class);

    private final List<String> observed = new ArrayList<>();

    private RecipeSearchService service(String engine) {
        FamilyMemberProfile profile = mock(FamilyMemberProfile.class, RETURNS_DEEP_STUBS);
        when(profile.getFamilyRoom().getId()).thenReturn(1L);
        when(profileRepository.findByMember_Id(anyLong())).thenAnswer(inv -> {
            observed.add("profile:" + inTx());
            return Optional.of(profile);
        });
        when(allergyRiskService.getFamilyAllergens(anyLong())).thenReturn(List.<Allergen>of());
        when(recipeRepository.findByTitleContainingIgnoreCase(anyString(), any())).thenAnswer(inv -> {
            observed.add("recipeDb:" + inTx());
            return List.of();
        });
        when(transformedRecipeRepository.findByTitleLike(anyString(), any())).thenReturn(List.of());
        when(externalClient.searchByName(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            observed.add("external:" + inTx());
            return List.of();
        });
        when(elasticSearchService.search(anyString(), any())).thenAnswer(inv -> {
            observed.add("es:" + inTx());
            return List.<RecipeDocument>of();
        });

        RecipeSearchService service = new RecipeSearchService(
                recipeRepository, transformedRecipeRepository, mock(RecipeExternalMetadataRepository.class),
                externalClient, allergyRiskService, profileRepository, mock(SearchLogService.class),
                elasticSearchService, new SimpleMeterRegistry(), new MarkerTransactionManager());
        ReflectionTestUtils.setField(service, "searchEngine", engine);
        return service;
    }

    @Test
    @DisplayName("MySQL 검색: DB 조회는 트랜잭션 안, 외부 API 호출은 트랜잭션 밖")
    void externalCallIsOutsideTransaction() {
        service("mysql").search(1L, "김치찌개", 0, 10);

        assertThat(observed).contains("profile:true", "recipeDb:true", "external:false");
    }

    @Test
    @DisplayName("ES 검색: ES 조회와 외부 API 호출 모두 트랜잭션 밖")
    void esAndExternalCallsAreOutsideTransaction() {
        service("elasticsearch").search(1L, "김치찌개", 0, 10);

        assertThat(observed).contains("profile:true", "es:false", "external:false");
        assertThat(observed).doesNotContain("es:true", "external:true");
    }
}

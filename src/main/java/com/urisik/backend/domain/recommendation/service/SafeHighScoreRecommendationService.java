package com.urisik.backend.domain.recommendation.service;

import com.urisik.backend.domain.recommendation.candidate.HighScoreRecommendationRecipeCandidate;
import com.urisik.backend.domain.recommendation.candidate.RecommendationRecipeCandidateLow;
import com.urisik.backend.domain.recommendation.candidate.RecommendationTransformedRecipeCandidateLow;
import com.urisik.backend.domain.recommendation.converter.HighScoreRecommendationConverter;
import com.urisik.backend.domain.recommendation.dto.res.HighScoreRecommendationResponseDTO;
import com.urisik.backend.domain.recommendation.policy.CategoryMapper;
import com.urisik.backend.domain.recommendation.policy.UnifiedCategory;
import com.urisik.backend.domain.recommendation.repository.HomeRecommendationRepository;
import com.urisik.backend.domain.recommendation.repository.HomeTransformedRecommendationRecipeRepository;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.service.AllergyRiskService;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urisik.backend.domain.allergy.enums.Allergen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafeHighScoreRecommendationService {

    private final HomeRecommendationRepository homeRecommendationRepository;
    private final HomeTransformedRecommendationRecipeRepository homeTransformedRecommendationRecipeRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergyRiskService allergyRiskService;
    private final HighScoreRecommendationConverter converter;

    /**
     * 알레르기 안전 + 카테고리 기준 레시피 추천
     */
    @Cacheable(value = "recommendSafeHighScore", key = "#loginUserId + ':' + #category")
    public HighScoreRecommendationResponseDTO recommendSafeRecipes(
            Long loginUserId,
            String category
    ) {
        // 1️. 로그인 사용자 → 가족방
        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();
        String normalizedCategory = normalizeCategory(category);

        Pageable pageable = PageRequest.of(0, 50);
        List<HighScoreRecommendationRecipeCandidate> candidates = new ArrayList<>();

        /* =========================
         * 2️. 카테고리 기준 후보 수집
         * ========================= */
        if (normalizedCategory == null) {
            // 카테고리 미선택 → 전체
            candidates.addAll(
                    homeRecommendationRepository.findTopByScore(pageable)
                            .stream()
                            .map(RecommendationRecipeCandidateLow::new)
                            .toList()
            );

            candidates.addAll(
                    homeTransformedRecommendationRecipeRepository.findTopByScore(pageable)
                            .stream()
                            .map(RecommendationTransformedRecipeCandidateLow::new)
                            .toList()
            );
        } else {
            List<String> legacyCategories =
                    CategoryMapper.toLegacyList(normalizedCategory);

            candidates.addAll(
                    homeRecommendationRepository.findTopByCategories(legacyCategories, pageable)
                            .stream()
                            .map(RecommendationRecipeCandidateLow::new)
                            .toList()
            );

            candidates.addAll(
                    homeTransformedRecommendationRecipeRepository.findTopByCategories(legacyCategories, pageable)
                            .stream()
                            .map(RecommendationTransformedRecipeCandidateLow::new)
                            .toList()
            );
        }

        /* =========================
         * 3. 알레르기 안전 필터 (핵심 차이)
         * ========================= */
        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);
        List<HighScoreRecommendationRecipeCandidate> safeCandidates =
                new ArrayList<>(
                        candidates.stream()
                                .filter(c ->
                                        allergyRiskService
                                                .detectRiskAllergens(
                                                        familyAllergens,
                                                        c.getIngredients()
                                                )
                                                .isEmpty()
                                )
                                .toList()
                );

        /* =========================
         * 4️. 정렬 기준 유지
         * ========================= */
        safeCandidates.sort(
                Comparator
                        .comparingDouble(HighScoreRecommendationRecipeCandidate::getAvgScore).reversed()
                        .thenComparingInt(HighScoreRecommendationRecipeCandidate::getReviewCount).reversed()
                        .thenComparingInt(HighScoreRecommendationRecipeCandidate::getWishCount).reversed()
        );

        /* =========================
         * 5️. 결과 반환
         * ========================= */
        return new HighScoreRecommendationResponseDTO(
                safeCandidates.stream()
                        .limit(3)
                        .map(c -> converter.toDto(c, true)) // 항상 안전
                        .toList()
        );
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }

        return switch (category) {
            case UnifiedCategory.BOWL,
                 UnifiedCategory.SOUP,
                 UnifiedCategory.SIDE,
                 UnifiedCategory.DESSERT -> category;
            default -> UnifiedCategory.ETC;
        };
    }
}

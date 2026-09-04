package com.urisik.backend.domain.recommendation.service;

import com.urisik.backend.domain.recommendation.candidate.*;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HighScoreRecommendationService {

    private final HomeRecommendationRepository homeRecommendationRepository;
    private final HomeTransformedRecommendationRecipeRepository homeTransformedRecommendationRecipeRepository;
    private final HighScoreRecommendationConverter converter;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergyRiskService allergyRiskService;

    /**
     * 고평점 레시피 추천
     *
     * 규칙:
     * 1) 카테고리 미입력 → 전체 레시피 중 Top 3
     * 2) 카테고리 입력 → 해당 카테고리 내 Top 3
     * 3) 정렬 기준
     *    - 평점 desc
     *    - 리뷰 수 desc
     *    - 위시 수 desc
     * 4) 위 3개 값이 모두 같을 때만
     *    → 알레르기 "위험 없는" 레시피 우선
     */
    @Cacheable(value = "recommendHighScore", key = "#loginUserId + ':' + #category")
    public HighScoreRecommendationResponseDTO recommend(
            Long loginUserId,
            String category
    ) {
        String normalizedCategory = normalizeCategory(category);


        // 로그인 사용자 + 가족방 확인
        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();

        Pageable pageable = PageRequest.of(0, 20);
        List<HighScoreRecommendationRecipeCandidate> candidates = new ArrayList<>();

        /* =========================
         * 1️. 후보 수집
         * ========================= */
        if (normalizedCategory == null) {
            // 카테고리 미입력: 전체
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
            // 카테고리 입력
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
         * 2️. 1차 정렬
         * 평점 → 리뷰 수 → 위시 수
         * ========================= */
        candidates.sort(
                Comparator.comparingDouble(HighScoreRecommendationRecipeCandidate::getAvgScore).reversed()
                        .thenComparingInt(HighScoreRecommendationRecipeCandidate::getReviewCount).reversed()
                        .thenComparingInt(HighScoreRecommendationRecipeCandidate::getWishCount).reversed()
        );

        /* =========================
         * 3️. 완전 동점자 알레르기 tie-break
         * ========================= */
        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);
        List<HighScoreRecommendationRecipeCandidate> finalSorted =
                applyAllergyTieBreaker(candidates, familyAllergens);

        /* =========================
         * 4. Top 3 반환
         * ========================= */
        return new HighScoreRecommendationResponseDTO(
                finalSorted.stream()
                        .limit(3)
                        .map(c -> {
                            boolean isSafe =
                                    allergyRiskService
                                            .detectRiskAllergens(
                                                    familyAllergens,
                                                    c.getIngredients()
                                            )
                                            .isEmpty();
                            return converter.toDto(c, isSafe);
                        })
                        .collect(Collectors.toCollection(ArrayList::new))
        );
    }

    /**
     * 평점 + 리뷰 수 + 위시 수가 모두 같은 "연속 구간"에 대해서만
     * 알레르기 위험 없는 레시피를 우선 배치
     *
     * ⚠전제:
     * - candidates 는 이미 점수 기준으로 정렬되어 있음
     */
    private List<HighScoreRecommendationRecipeCandidate> applyAllergyTieBreaker(
            List<HighScoreRecommendationRecipeCandidate> candidates,
            List<Allergen> familyAllergens
    ) {
        if (candidates.size() <= 1) return candidates;

        List<HighScoreRecommendationRecipeCandidate> result = new ArrayList<>();
        int i = 0;

        while (i < candidates.size()) {
            HighScoreRecommendationRecipeCandidate base = candidates.get(i);

            // 같은 점수 그룹 찾기
            List<HighScoreRecommendationRecipeCandidate> sameRankGroup = new ArrayList<>();
            sameRankGroup.add(base);

            int j = i + 1;
            while (j < candidates.size()
                    && base.getAvgScore() == candidates.get(j).getAvgScore()
                    && base.getReviewCount() == candidates.get(j).getReviewCount()
                    && base.getWishCount() == candidates.get(j).getWishCount()
            ) {
                sameRankGroup.add(candidates.get(j));
                j++;
            }

            // 동점자 그룹 내에서만 알레르기 안전 우선
            if (sameRankGroup.size() > 1) {
                sameRankGroup.sort(
                        (a, b) -> Boolean.compare(
                                isSafeRecipe(familyAllergens, b),
                                isSafeRecipe(familyAllergens, a)
                        )
                );
            }


            result.addAll(sameRankGroup);
            i = j;
        }

        return result;
    }

    /**
     * 알레르기 위험 여부 판단
     * - 위험 알레르기 없음 → true (안전)
     * - 하나라도 있으면 → false (위험)
     */
    private boolean isSafeRecipe(
            List<Allergen> familyAllergens,
            HighScoreRecommendationRecipeCandidate candidate
    ) {
        return allergyRiskService
                .detectRiskAllergens(
                        familyAllergens,
                        candidate.getIngredients()
                )
                .isEmpty();
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



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
public class WishRecommendationService {

    private final HomeRecommendationRepository homeRecommendationRepository;
    private final HomeTransformedRecommendationRecipeRepository transformedRecipeRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergyRiskService allergyRiskService;
    private final HighScoreRecommendationConverter converter;

    @Cacheable(value = "recommendWish", key = "#loginUserId + ':' + #category")
    public HighScoreRecommendationResponseDTO recommend(
            Long loginUserId,
            String category
    ) {
        // 1. 로그인 사용자 → 가족방
        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();
        String normalizedCategory = normalizeCategory(category);

        Pageable pageable = PageRequest.of(0, 30);
        List<HighScoreRecommendationRecipeCandidate> candidates = new ArrayList<>();

        // 2. 후보 수집 (위시 기준 정렬은 DB에서)
        if (normalizedCategory == null) {
            candidates.addAll(
                    homeRecommendationRepository.findTopByWish(pageable)
                            .stream()
                            .map(RecommendationRecipeCandidateLow::new)
                            .toList()
            );
            candidates.addAll(
                    transformedRecipeRepository.findTopByWish(pageable)
                            .stream()
                            .map(RecommendationTransformedRecipeCandidateLow::new)
                            .toList()
            );
        } else {
            List<String> legacyCategories = CategoryMapper.toLegacyList(normalizedCategory);

            candidates.addAll(
                    homeRecommendationRepository.findTopByWishAndCategories(legacyCategories, pageable)
                            .stream()
                            .map(RecommendationRecipeCandidateLow::new)
                            .toList()
            );
            candidates.addAll(
                    transformedRecipeRepository.findTopByWishAndCategories(legacyCategories, pageable)
                            .stream()
                            .map(RecommendationTransformedRecipeCandidateLow::new)
                            .toList()
            );
        }

        // 3. 최종 정렬 (알레르기 X)
        candidates.sort(
                Comparator.comparingInt(HighScoreRecommendationRecipeCandidate::getWishCount).reversed()
                        .thenComparingDouble(HighScoreRecommendationRecipeCandidate::getAvgScore).reversed()
                        .thenComparingInt(HighScoreRecommendationRecipeCandidate::getReviewCount).reversed()
        );

        // 4. Top 3 + 알레르기 여부 표시만
        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);
        return new HighScoreRecommendationResponseDTO(
                candidates.stream()
                        .limit(3)
                        .map(c -> {
                            boolean isSafe =
                                    allergyRiskService
                                            .detectRiskAllergens(familyAllergens, c.getIngredients())
                                            .isEmpty();
                            return converter.toDto(c, isSafe);
                        })
                        .toList()
        );
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;

        return switch (category) {
            case UnifiedCategory.BOWL,
                 UnifiedCategory.SOUP,
                 UnifiedCategory.SIDE,
                 UnifiedCategory.DESSERT -> category;
            default -> UnifiedCategory.ETC;
        };
    }
}


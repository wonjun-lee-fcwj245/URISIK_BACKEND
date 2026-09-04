package com.urisik.backend.domain.recommendation.service;

import com.urisik.backend.domain.recommendation.candidate.HomeRecommendationRecipeCandidate;
import com.urisik.backend.domain.recommendation.candidate.RecommendationRecipeCandidate;
import com.urisik.backend.domain.recommendation.candidate.RecommendationTransformedRecipeCandidate;
import com.urisik.backend.domain.recommendation.repository.HomeRecommendationRepository;
import com.urisik.backend.domain.recommendation.repository.HomeTransformedRecommendationRecipeRepository;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recommendation.converter.HomeSafeRecipeConverter;
import com.urisik.backend.domain.recommendation.dto.HomeSafeRecommendationRecipeDTO;
import com.urisik.backend.domain.recommendation.dto.res.HomeSafeRecipeResponseDTO;
import com.urisik.backend.domain.recipe.service.AllergyRiskService;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urisik.backend.domain.allergy.enums.Allergen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HomeRecommendationService {

    private final HomeRecommendationRepository homeRecommendationRepository;
    private final HomeTransformedRecommendationRecipeRepository transformedRecipeRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergyRiskService allergyRiskService;
    private final HomeSafeRecipeConverter converter;

    @Cacheable(value = "recommendSafe", key = "#loginUserId")
    @Transactional(readOnly = true)
    public HomeSafeRecipeResponseDTO recommendSafeRecipes(Long loginUserId) {

        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() ->
                                new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();

        List<HomeRecommendationRecipeCandidate> candidates = new ArrayList<>();

        candidates.addAll(
                homeRecommendationRepository.findTopForHome(PageRequest.of(0, 20))
                        .stream()
                        .map(RecommendationRecipeCandidate::new)
                        .collect(Collectors.toList())
        );

        candidates.addAll(
                transformedRecipeRepository.findTopForHome(PageRequest.of(0, 20))
                        .stream()
                        .map(RecommendationTransformedRecipeCandidate::new)
                        .collect(Collectors.toList())
        );


        candidates.sort(
                Comparator.comparingInt(HomeRecommendationRecipeCandidate::getWishCount)
                        .reversed()
        );

        List<HomeSafeRecommendationRecipeDTO> result = new ArrayList<>();
        List<Allergen> familyAllergens = allergyRiskService.getFamilyAllergens(familyRoomId);

        for (HomeRecommendationRecipeCandidate candidate : candidates) {

            List<String> ingredients = candidate.getIngredients();

            boolean hasRisk =
                    !allergyRiskService
                            .detectRiskAllergens(familyAllergens, ingredients)
                            .isEmpty();

            if (!hasRisk) {
                result.add(converter.toDto(candidate));
            }

            if (result.size() == 3) break;
        }

        return new HomeSafeRecipeResponseDTO(result);
    }
}

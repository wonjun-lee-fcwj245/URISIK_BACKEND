package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.allergy.entity.MemberAllergy;
import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.allergy.repository.MemberAllergyRepository;
import com.urisik.backend.global.util.IngredientNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AllergyRiskService {

    private final MemberAllergyRepository memberAllergyRepository;
    private final IngredientNormalizer ingredientNormalizer;

    /** 가족방의 알레르기 목록을 1회 조회하여 반환 (N+1 방지용 사전 조회) */
    @Cacheable(value = "familyAllergens", key = "#familyRoomId")
    public List<Allergen> getFamilyAllergens(Long familyRoomId) {
        return memberAllergyRepository.findByFamilyRoomId(familyRoomId)
                .stream()
                .map(MemberAllergy::getAllergen)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<Allergen> detectRiskAllergens(Long familyRoomId, List<String> ingredients) {
        List<Allergen> familyAllergens =
                memberAllergyRepository.findByFamilyRoomId(familyRoomId)
                        .stream()
                        .map(MemberAllergy::getAllergen)
                        .distinct()
                        .toList();

        return detectRiskAllergens(familyAllergens, ingredients);
    }

    /** N+1 방지를 위해 가족 알레르기 목록을 외부에서 1회 조회해 주입할 수 있는 overload */
    public List<Allergen> detectRiskAllergens(List<Allergen> familyAllergens, List<String> ingredients) {
        if (familyAllergens == null || familyAllergens.isEmpty()) return List.of();
        if (ingredients == null || ingredients.isEmpty()) return List.of();

        List<String> normalized =
                ingredients.stream().map(ingredientNormalizer::normalize).toList();

        return familyAllergens.stream()
                .filter(a -> normalized.stream().anyMatch(a::matchesIngredient))
                .toList();
    }

    // 개인 전용 알러지 탐색기
    public List<Allergen> detectRiskAllergensForOne(Long profileId, List<String> ingredients) {
        List<Allergen> personalAllergens =
                memberAllergyRepository.findByFamilyMemberProfile_Id(profileId)
                        .stream()
                        .map(MemberAllergy::getAllergen)
                        .distinct()
                        .toList();

        if (personalAllergens.isEmpty()) return List.of();

        List<String> normalized =
                ingredients.stream().map(ingredientNormalizer::normalize).toList();

        return personalAllergens.stream()
                .filter(a -> normalized.stream().anyMatch(a::matchesIngredient))
                .toList();

    }
}

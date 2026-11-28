package com.urisik.backend.allergy;

/*
 * [비활성] 테스트 대상 RecipeAllergyController가 main에서 클래스 본문 전체가 주석 처리돼 있어
 * (src/main/java/.../allergy/controller/RecipeAllergyController.java) 이 테스트가 컴파일되지 않았다.
 * 그 때문에 전체 테스트(gradle test)가 돌지 않던 문제를 막으려고 컨트롤러와 같은 방식으로 주석 처리한다.
 * 아래 코드는 주석 처리된 컨트롤러의 현재 코드(가족 프로필로 가족방을 찾고 generateSubstitutionRules로 판별)에 맞춰 고쳐 두었다.
 * 컨트롤러를 다시 켤 때 이 주석을 함께 풀면 된다.


import com.fasterxml.jackson.databind.ObjectMapper;
import com.urisik.backend.domain.allergy.controller.RecipeAllergyController;
import com.urisik.backend.domain.allergy.dto.req.RecipeAllergyCheckRequestDTO;
import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.allergy.enums.AllergySuccessCode;
import com.urisik.backend.domain.allergy.entity.AllergenAlternative;
import com.urisik.backend.domain.allergy.entity.AlternativeIngredient;
import com.urisik.backend.domain.allergy.service.AllergySubstitutionService;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsInAnyOrder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(RecipeAllergyController.class)
class RecipeAllergyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AllergySubstitutionService allergySubstitutionService;

    // 컨트롤러가 로그인 사용자의 가족 프로필로 가족방을 찾은 뒤 가족방 기준으로 판별한다
    @MockBean
    private FamilyMemberProfileRepository familyMemberProfileRepository;

    private static final Long FAMILY_ROOM_ID = 10L;

    private void givenFamilyRoom(Long loginUserId) {
        FamilyMemberProfile profile = Mockito.mock(FamilyMemberProfile.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(profile.getFamilyRoom().getId()).thenReturn(FAMILY_ROOM_ID);
        Mockito.when(familyMemberProfileRepository.findByMember_Id(loginUserId)).thenReturn(Optional.of(profile));
    }

    private static AllergenAlternative alternative(String name, String reason) {
        AlternativeIngredient ingredient = Mockito.mock(AlternativeIngredient.class);
        Mockito.when(ingredient.getName()).thenReturn(name);
        AllergenAlternative alt = Mockito.mock(AllergenAlternative.class);
        Mockito.when(alt.getIngredient()).thenReturn(ingredient);
        Mockito.when(alt.getReason()).thenReturn(reason);
        return alt;
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("레시피에 알레르기 식품이 포함된 경우 대체 식재료를 반환한다")
    @WithMockUser(username = "1")
    void recipeAllergyCheck_success() throws Exception {
        // given
        Long loginUserId = 1L;

        RecipeAllergyCheckRequestDTO request =
                new RecipeAllergyCheckRequestDTO(List.of(
                        "고구마 100g(2/3개)",
                        "설탕 2g",
                        "잣 8g(8알)"
                ));

        givenFamilyRoom(loginUserId);
        Map<Allergen, List<AllergenAlternative>> serviceResult = Map.of(
                Allergen.PINE_NUT,
                List.of(alternative("참깨", "고소한 맛"), alternative("해바라기씨", "식감"))
        );

        Mockito.when(
                allergySubstitutionService.generateSubstitutionRules(
                        Mockito.eq(FAMILY_ROOM_ID),
                        Mockito.anyList()
                )
        ).thenReturn(serviceResult);

        // when & then
        mockMvc.perform(post("/api/recipes/allergy-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(() -> loginUserId.toString())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code")
                        .value(AllergySuccessCode.RECIPE_ALLERGY_CHECK_OK.getCode()))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].allergen").value("잣"))
                .andExpect(jsonPath("$.result[0].alternatives", hasSize(2)))
                .andExpect(jsonPath("$.result[0].alternatives[*].name",
                        containsInAnyOrder("참깨", "해바라기씨")));
    }

    @Test
    @DisplayName("레시피에 알레르기 식품이 없는 경우 빈 리스트를 반환한다")
    @WithMockUser(username = "1")
    void recipeAllergyCheck_noAllergy() throws Exception {
        // given
        Long loginUserId = 1L;

        RecipeAllergyCheckRequestDTO request =
                new RecipeAllergyCheckRequestDTO(List.of(
                        "고구마 100g",
                        "설탕 2g"
                ));

        givenFamilyRoom(loginUserId);
        Mockito.when(
                allergySubstitutionService.generateSubstitutionRules(
                        Mockito.eq(FAMILY_ROOM_ID),
                        Mockito.anyList()
                )
        ).thenReturn(Map.of());

        // when & then
        mockMvc.perform(post("/api/recipes/allergy-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(() -> loginUserId.toString())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result", hasSize(0)));
    }

}

*/

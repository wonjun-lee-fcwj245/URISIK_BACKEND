package com.urisik.backend.allergy;

import com.urisik.backend.domain.allergy.controller.AllergyController;
import com.urisik.backend.domain.allergy.dto.res.AllergyResponseDTO;
import com.urisik.backend.domain.allergy.service.AllergyQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(AllergyController.class)
@AutoConfigureMockMvc
class AllergyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AllergyQueryService allergyQueryService;

    /**
     *본인 알레르기 조회 성공
     */
    @Test
    void getUserAllergies_success() throws Exception {
        // given
        Long userId = 1L;

        List<AllergyResponseDTO> response =
                List.of(
                        new AllergyResponseDTO("우유"),
                        new AllergyResponseDTO("계란")
                );

        given(allergyQueryService.getMyAllergies(userId))
                .willReturn(response);

        // when & then
        mockMvc.perform(
                        get("/api/members/{memberId}/allergies", userId)
                                .with(authentication(authenticationWithPrincipal(userId)))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("ALLERGY_200_001"))
                .andExpect(jsonPath("$.result[0].name").value("우유"))
                .andExpect(jsonPath("$.result[1].name").value("계란"));
    }

    /**
     * 다른 사용자 알레르기 조회 → 403
     */
    @Test
    void getUserAllergies_forbidden() throws Exception {
        // given
        Long loginUserId = 1L;
        Long targetUserId = 2L;

        // when & then
        mockMvc.perform(
                        get("/api/members/{memberId}/allergies", targetUserId)
                                .with(authentication(authenticationWithPrincipal(loginUserId)))
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_403_001"));
    }

    /**
     * 인증 정보 없음 → 401
     */
    @Test
    void getUserAllergies_unauthorized() throws Exception {
        mockMvc.perform(
                        get("/api/members/{memberId}/allergies", 1L)
                )
                .andExpect(status().isUnauthorized());
    }

    /**
     * 테스트용 Authentication 생성
     */
    private Authentication authenticationWithPrincipal(Long memberId) {
        return new UsernamePasswordAuthenticationToken(
                memberId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}


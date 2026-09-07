package com.urisik.backend.global.auth.controller;

import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.entity.Member;
import com.urisik.backend.domain.member.entity.MemberTransformedRecipeWish;
import com.urisik.backend.domain.member.entity.MemberWishList;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.member.repo.MemberRepository;
import com.urisik.backend.domain.member.repo.MemberTransformedRecipeWishRepository;
import com.urisik.backend.domain.member.repo.MemberWishListRepository;
import com.urisik.backend.global.apiPayload.ApiResponse;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.code.GeneralSuccessCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import com.urisik.backend.global.auth.dto.AccessTokenDto;
import com.urisik.backend.global.auth.dto.res.LogoutResponse;
import com.urisik.backend.global.auth.exception.AuthenExcetion;
import com.urisik.backend.global.auth.exception.code.AuthErrorCode;
import com.urisik.backend.global.auth.exception.code.AuthSuccessCode;
import com.urisik.backend.global.auth.jwt.JwtUtil;
import com.urisik.backend.global.auth.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

import java.util.List;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "인증 관련 API")
public class AuthController {

    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AuthService authService;
    private final Environment environment;

    @PostMapping("/reissue")
    public ApiResponse<AccessTokenDto> reissue(
            @CookieValue(value = "refresh_token" , required = false) String refreshToken) {


        // 비어있거나, 유효하거나 , 리프레시 토큰(어쎄스로 속일수도) 이어야함
        if (refreshToken == null) {
            throw new AuthenExcetion(AuthErrorCode.NO_TOKEN);
        }
        if(!jwtUtil.isValid(refreshToken)){
            throw new AuthenExcetion(AuthErrorCode.TOKEN_NOT_VALID);
        }
        if(!jwtUtil.isRefresh(refreshToken)){
            throw new AuthenExcetion(AuthErrorCode.NOT_REFRESH_TOKEN);

        }




        Long memberId = jwtUtil.getMemberId(refreshToken);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));

        boolean needAgreement =
                !member.isServiceTermsAgreed()
                        || !member.isPrivacyPolicyAgreed()
                        || !member.isFamilyInfoAgreed()
                        || !member.isAiNoticeAgreed();



        String accessToken = jwtUtil.createAccessToken(memberId, member.getRole());

        AccessTokenDto.builder()
                .accessToken(accessToken)
                .needAgreement(needAgreement)
                .serviceTermsAgreed(member.isServiceTermsAgreed())
                .privacyPolicyAgreed(member.isPrivacyPolicyAgreed())
                .familyInfoAgreed(member.isFamilyInfoAgreed())
                .aiNoticeAgreed(member.isAiNoticeAgreed())
                .marketingOptIn(member.isMarketingOptIn())
                .build();

        return ApiResponse.onSuccess(AuthSuccessCode.Login_Access_Token,
                AccessTokenDto.builder()
                        .accessToken(accessToken)
                        .needAgreement(needAgreement)
                        .serviceTermsAgreed(member.isServiceTermsAgreed())
                        .privacyPolicyAgreed(member.isPrivacyPolicyAgreed())
                        .familyInfoAgreed(member.isFamilyInfoAgreed())
                        .aiNoticeAgreed(member.isAiNoticeAgreed())
                        .marketingOptIn(member.isMarketingOptIn())
                        .build()
                        );
    }
    @PostMapping("/logout")
    public ApiResponse<LogoutResponse> logout(HttpServletResponse response) {

        Cookie cookie = new Cookie("refresh_token", null);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true); // 운영 HTTPS면 true, 로컬 http 테스트면 false로 해야 브라우저가 안 막음
        cookie.setMaxAge(0);    // ✅ 삭제
        response.addCookie(cookie);

        SecurityContextHolder.clearContext(); // 선택

        return ApiResponse.onSuccess(
                AuthSuccessCode.Logout_Suc,
                LogoutResponse.builder().logoutSuccess(true).deleteSuccess(false).build()
        );
    }


    /**
     * k6 부하 테스트 전용 토큰 발급 — prod 프로필에서는 동작하지 않음
     */
    @PostMapping("/test-token")
    public ApiResponse<AccessTokenDto> testToken(@RequestParam Long memberId) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));

        String accessToken = jwtUtil.createAccessToken(memberId, member.getRole());

        return ApiResponse.onSuccess(AuthSuccessCode.Login_Access_Token,
                AccessTokenDto.builder()
                        .accessToken(accessToken)
                        .needAgreement(false)
                        .serviceTermsAgreed(true)
                        .privacyPolicyAgreed(true)
                        .familyInfoAgreed(true)
                        .aiNoticeAgreed(true)
                        .marketingOptIn(false)
                        .build()
        );
    }

    @PostMapping("/delete")
    public ApiResponse<LogoutResponse> withdraw(HttpServletResponse response) {

        // ✅ JwtAuthFilter에서 principal = memberId 넣어둔 상태라고 가정
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getPrincipal() == null) {
            throw new AuthenExcetion(AuthErrorCode.NO_TOKEN);
        }

        Long memberId;
        try {
            memberId = (Long) auth.getPrincipal();
        } catch (ClassCastException e) {
            throw new AuthenExcetion(AuthErrorCode.NO_TOKEN);
        }

        // ✅ 회원 조회
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));

        // ✅ (중요) 연관 데이터 때문에 hard delete 실패할 수 있음
        // 2) hard delete면 cascade/orphanRemoval / FK on delete cascade 정리가 필요


        authService.withdraw(memberId);


        // ✅ refresh_token 쿠키 삭제 내려주기
        Cookie cookie = new Cookie("refresh_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);       // https 운영이면 true 유지
        cookie.setPath("/");
        cookie.setMaxAge(0);          // 삭제
        response.addCookie(cookie);

        return ApiResponse.onSuccess(
                AuthSuccessCode.Auth_delete_Suc,
                LogoutResponse.builder().logoutSuccess(true).deleteSuccess(true).build()
        );
    }


}

package com.urisik.backend.global.auth;


import com.urisik.backend.global.auth.jwt.JwtAuthFilter;
import com.urisik.backend.global.auth.jwt.JwtUtil;
import com.urisik.backend.global.auth.oauth2.CustomSuccessHandler;
import com.urisik.backend.global.auth.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig  {

    //소셜 로그인 회원 인증 만들기 절차
    private final CustomOAuth2UserService customOAuth2UserService;
    //JWT 토큰 생성 검증 절차
    private final JwtUtil jwtUtil;
    //JWT 토큰 쿠키에 담기
    private final CustomSuccessHandler customSuccessHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())

                .cors(cors -> {}) // 아래 corsConfigurationSource()랑 연결

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)

                ) // 🔥 JWT 인증에서는 세션을 절대 사용하지 않게 설정// 🛑 HTML 폼 로그인 / 기본 로그아웃 비활성화

                .formLogin(form -> form.disable())

                // ✅ 인증 실패 시 /login 리다이렉트 대신 401 내려주기 (중요)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"message\":\"Unauthorized\"}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"message\":\"Forbidden\"}");
                        })
                )

                .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class)


                .oauth2Login((oauth2) -> oauth2
                        .userInfoEndpoint((userInfoEndpointConfig) -> userInfoEndpointConfig
                                .userService(customOAuth2UserService))
                        .successHandler(customSuccessHandler)
                )
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // ✅ CORS preflight는 무조건 허용
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()

                        // ✅ 공개 엔드포인트(로그인 관련/문서)
                        .requestMatchers(
                                "/oauth2/authorize/**",
                                "/login/oauth2/**",          // provider callback 경로(환경에 따라 필요)
                                "/api/auth/**",
                                "/actuator/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/css/**", "/js/**", "/images/**"
                        ).permitAll()

                        // ✅ 나머지 전부 로그인 필수
                        .anyRequest().authenticated()

                );
        return http.build();
    }


    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(jwtUtil);
    }


    // 🌐 CORS 설정 (프론트 도메인 넣기)
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var config = new org.springframework.web.cors.CorsConfiguration();

        config.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "https://urisik.vercel.app",
                "https://api.urisik.com"
        ));
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("X-Has-Next", "X-Next-Cursor", "X-AI-Used",
                "X-AI-Client"));

        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }


}





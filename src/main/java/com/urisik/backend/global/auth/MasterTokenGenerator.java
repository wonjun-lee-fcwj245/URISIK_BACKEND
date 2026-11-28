package com.urisik.backend.global.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public class MasterTokenGenerator {

    public static void main(String[] args) {

        // 서명 키는 소스에 적지 않고 환경 변수 JWT_KEY(앱이 쓰는 값과 동일)에서 읽는다.
        // 예: JWT_KEY=... java ... MasterTokenGenerator   (키가 공개 저장소에 올라간 적이 있어 교체했다면 새 키를 쓴다)
        String secret = System.getenv("JWT_KEY");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("환경 변수 JWT_KEY(32바이트 이상)를 설정하세요. 키를 소스에 적지 마세요.");
        }

        Long masterMemberId = 1L;       //  마스터 계정 id
        String role = "ROLE_ADMIN";     //  권한

        SecretKey secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        Date now = new Date();
        Date expiry = Date.from(
                LocalDateTime.now()
                        .plusMonths(2)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
        );

        String token = Jwts.builder()
                .subject(String.valueOf(masterMemberId))
                .claim("role", role)
                .claim("typ", "access")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();

        String tokend = Jwts.builder()
                .subject(String.valueOf(masterMemberId))
                .claim("typ", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();


        System.out.println("====== MASTER JWT (2 months) ======");
        System.out.println(token);
        System.out.println("expiresAt = " + expiry);

        System.out.println("====== MASTER JWT (2 months) ======");
        System.out.println(tokend);
        System.out.println("expiresAt = " + expiry);
    }
}

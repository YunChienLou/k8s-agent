package com.legacyai.adapter;

import java.time.Instant;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 測試用的「SSO」替身，與舊系統測試相同慣例。
 * <p>
 * Bearer token 直接寫成「使用者~client」，例如 {@code alice~copilot-web}；
 * token 為 {@code bad} 時模擬簽章錯誤。請求仍會經過真正的 Spring Security 過濾器。
 */
final class TestAuth {
    private TestAuth() {}

    static final String DEFAULT_CLIENT = "copilot-web";

    static Jwt decode(String token) {
        if ("bad".equals(token)) {
            throw new BadJwtException("簽章錯誤");
        }
        String[] parts = token.split("~", 2);
        String user = parts[0];
        String client = parts.length > 1 ? parts[1] : DEFAULT_CLIENT;
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("sub-" + user)
            .claim("preferred_username", user)
            .claim("azp", client)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .build();
    }
}

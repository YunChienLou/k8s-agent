package com.legacyai.logistics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 測試用的「SSO」與「profile API」替身。
 * <p>
 * Bearer token 直接寫成「使用者~client」（@ 不是 RFC 6750 允許的 token 字元），例如 {@code alice~copilot-web}；沒有 ~ 時 client 為 {@link #DEFAULT_CLIENT}。
 * token 為 {@code bad} 時模擬簽章錯誤。請求仍會經過真正的 Spring Security 過濾器與角色轉換。
 */
final class TestAuth {
    private TestAuth() {}

    static final String DEFAULT_CLIENT = "logistics-web";

    /** profile API 對本系統（logistics）回傳的角色。 */
    static final Map<String, List<String>> ROLES = Map.of(
        "alice", List.of("cs_agent"),
        "bob", List.of("cs_agent"),
        "carol", List.of("cs_supervisor"),
        "wang", List.of("logistics_staff"));

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

    static List<String> roles(Jwt jwt) {
        return ROLES.getOrDefault(jwt.getClaimAsString("preferred_username"), List.of());
    }
}

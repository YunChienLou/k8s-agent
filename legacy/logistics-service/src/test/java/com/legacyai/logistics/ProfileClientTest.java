package com.legacyai.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** profile API 連不上時要 fail closed：視為沒有角色（→ 403），不能放行。 */
class ProfileClientTest {

    @Test
    @DisplayName("profile API 無法連線 → 回傳空角色（fail closed）")
    void failClosedWhenUnreachable() {
        ProfileClient client = new ProfileClient("http://127.0.0.1:1", "logistics", Duration.ofSeconds(60));
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-alice")
            .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        assertThat(client.rolesOf(jwt)).isEmpty();
    }
}

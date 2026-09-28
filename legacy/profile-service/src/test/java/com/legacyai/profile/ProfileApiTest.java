package com.legacyai.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 公司 profile API：同一張 SSO token 查詢「這個人在各系統的角色」。 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileApiTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void fakeSso() {
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> {
            String user = inv.getArgument(0);
            if ("bad".equals(user)) {
                throw new BadJwtException("簽章錯誤");
            }
            Instant now = Instant.now();
            return Jwt.withTokenValue(user).header("alg", "RS256").subject("sub-" + user)
                .claim("preferred_username", user).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        });
    }

    @Test
    @DisplayName("未帶 token → 401；簽章錯誤 → 401")
    void unauthenticated() {
        assertThat(get("/api/me", null)).hasStatus(401);
        assertThat(get("/api/me", "bad")).hasStatus(401);
    }

    @Test
    @DisplayName("/api/me 回傳 alice 在每個系統的角色")
    void allSystems() {
        MvcTestResult r = get("/api/me", "alice");
        assertThat(r).hasStatus(200).bodyJson().extractingPath("$.username").isEqualTo("alice");
        assertThat(r).bodyJson().extractingPath("$.systems.ticket[0]").isEqualTo("cs_agent");
        assertThat(r).bodyJson().extractingPath("$.systems.logistics[0]").isEqualTo("cs_agent");
    }

    @Test
    @DisplayName("/api/me/roles 只回傳指定系統的角色")
    void singleSystem() {
        assertThat(get("/api/me/roles?system=logistics", "wang")).hasStatus(200).bodyText().isEqualTo("[\"logistics_staff\"]");
        assertThat(get("/api/me/roles?system=ticket", "wang")).hasStatus(200).bodyText().isEqualTo("[]");
    }

    @Test
    @DisplayName("不認識的使用者：沒有任何角色")
    void unknownUser() {
        assertThat(get("/api/me/roles?system=ticket", "mallory")).hasStatus(200).bodyText().isEqualTo("[]");
    }

    private MvcTestResult get(String url, String token) {
        var req = mvc.get().uri(url);
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req.exchange();
    }
}

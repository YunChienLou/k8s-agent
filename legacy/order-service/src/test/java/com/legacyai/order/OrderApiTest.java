package com.legacyai.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 訂單系統：驗證 SSO token，並以 profile API 的角色決定能否查詢。 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean ProfileClient profile;

    @BeforeEach
    void fakeSsoAndProfile() {
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> TestAuth.decode(inv.getArgument(0)));
        given(profile.rolesOf(any(Jwt.class))).willAnswer(inv -> TestAuth.roles(inv.getArgument(0)));
    }

    @Test
    @DisplayName("未帶 token → 401；簽章錯誤 → 401")
    void unauthenticated() {
        assertThat(get("/api/orders/O-2001", null)).hasStatus(401);
        assertThat(get("/api/orders/O-2001", "bad")).hasStatus(401);
    }

    @Test
    @DisplayName("在訂單系統沒有角色的人（dave）→ 403")
    void noRoleInThisSystem() {
        assertThat(get("/api/orders/O-2001", "dave")).hasStatus(403);
    }

    @Test
    @DisplayName("客服查訂單 O-2001")
    void agentReadsOrder() {
        assertThat(get("/api/orders/O-2001", "alice"))
            .hasStatus(200)
            .bodyJson().extractingPath("$.shipmentId").isEqualTo("S-3001");
    }

    @Test
    @DisplayName("依客戶查訂單，只回傳該客戶的訂單")
    void listByCustomer() {
        assertThat(get("/api/orders?customerId=C-001", "alice"))
            .hasStatus(200).bodyText().contains("O-2001").contains("O-2002").doesNotContain("O-2003");
    }

    @Test
    @DisplayName("查無訂單 → 404")
    void notFound() {
        assertThat(get("/api/orders/O-9999", "alice")).hasStatus(404);
    }

    @Test
    @DisplayName("經由 Copilot 的 token 一樣可以查詢，權限仍由 profile API 決定")
    void viaCopilot() {
        assertThat(get("/api/orders/O-2001", "alice~copilot-web")).hasStatus(200);
        assertThat(get("/api/orders/O-2001", "dave~copilot-web")).hasStatus(403);
    }

    private MvcTestResult get(String url, String token) {
        var req = mvc.get().uri(url);
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req.exchange();
    }
}

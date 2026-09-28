package com.legacyai.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 工單權限與補償流程：上限內直接生效，超過則進入主管簽核。資料在記憶體中，每個測試重建 context。 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TicketAndCompensationTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean ProfileClient profile;

    @BeforeEach
    void fakeSsoAndProfile() {
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> TestAuth.decode(inv.getArgument(0)));
        given(profile.rolesOf(any(Jwt.class))).willAnswer(inv -> TestAuth.roles(inv.getArgument(0)));
    }

    /* ---------- 認證與工單權限 ---------- */

    @Test
    @DisplayName("未帶 token → 401；簽章錯誤 → 401")
    void unauthenticated() {
        assertThat(get("/api/tickets/T-1001", null)).hasStatus(401);
        assertThat(get("/api/tickets/T-1001", "bad")).hasStatus(401);
    }

    @Test
    @DisplayName("在工單系統沒有角色的人（物流人員 wang）→ 403")
    void noRoleInThisSystem() {
        assertThat(get("/api/tickets/T-1001", "wang")).hasStatus(403);
    }

    @Test
    @DisplayName("客服只看得到指派給自己的工單")
    void agentSeesOwnTickets() {
        assertThat(get("/api/tickets", "alice")).hasStatus(200).bodyText()
            .contains("T-1001").contains("T-1003").doesNotContain("T-1002");
    }

    @Test
    @DisplayName("alice 查 bob 的工單 → 403；主管 carol 可以查")
    void crossAssigneeAccess() {
        assertThat(get("/api/tickets/T-1002", "alice")).hasStatus(403);
        assertThat(get("/api/tickets/T-1002", "carol")).hasStatus(200);
    }

    /* ---------- 補償 ---------- */

    @Test
    @DisplayName("補償 60 元（在客服上限內）→ 201 直接生效")
    void withinLimitApplies() {
        assertThat(compensate("alice", 60, null))
            .hasStatus(201)
            .bodyJson().extractingPath("$.outcome").isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("補償 150 元（超過客服上限）→ 202 待主管簽核；客服不能核准；主管核准；重複核准 409")
    void overLimitGoesToSupervisor() {
        MvcTestResult created = compensate("alice", 150, null);
        assertThat(created).hasStatus(202).bodyJson().extractingPath("$.outcome").isEqualTo("PENDING_APPROVAL");
        String id = read(created, "$.request.id");

        assertThat(post("/api/compensation-requests/" + id + "/approve", "alice", null)).hasStatus(403);
        MvcTestResult approved = post("/api/compensation-requests/" + id + "/approve", "carol", null);
        assertThat(approved).hasStatus(200).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
        assertThat(read(approved, "$.compensationId")).startsWith("CP-");
        assertThat(post("/api/compensation-requests/" + id + "/approve", "carol", null)).hasStatus(409);
    }

    @Test
    @DisplayName("駁回必須填原因（400），填寫後駁回成功")
    void supervisorRejects() {
        String id = read(compensate("alice", 150, null), "$.request.id");
        assertThat(post("/api/compensation-requests/" + id + "/reject", "carol", "{\"reason\":\"\"}")).hasStatus(400);
        assertThat(post("/api/compensation-requests/" + id + "/reject", "carol", "{\"reason\":\"金額過高\"}"))
            .hasStatus(200)
            .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("超過主管上限 500 元：主管也不能核准 → 403")
    void overSupervisorLimit() {
        String id = read(compensate("carol", 600, null), "$.request.id");
        assertThat(post("/api/compensation-requests/" + id + "/approve", "carol", null)).hasStatus(403);
    }

    @Test
    @DisplayName("同一個 externalRef 重送 → 200 回傳既有結果（冪等）")
    void idempotentByExternalRef() {
        MvcTestResult first = compensate("alice", 30, "CF-TEST-1");
        assertThat(first).hasStatus(201);
        MvcTestResult again = compensate("alice", 30, "CF-TEST-1");
        assertThat(again).hasStatus(200);
        assertThat(read(again, "$.compensation.id")).isEqualTo(read(first, "$.compensation.id"));
    }

    @Test
    @DisplayName("經由 Copilot 送出：申請人仍是 alice，並記錄 azp=copilot-web")
    void viaCopilotRecordsClient() {
        MvcTestResult r = post("/api/tickets/T-1001/compensations", "alice~copilot-web",
            "{\"type\":\"COUPON\",\"amount\":150,\"reason\":\"客人很生氣\",\"channel\":\"AI_COPILOT\"}");
        assertThat(r).hasStatus(202);
        assertThat(r).bodyJson().extractingPath("$.request.requestedBy").isEqualTo("alice");
        assertThat(r).bodyJson().extractingPath("$.request.submittedVia").isEqualTo("copilot-web");
        assertThat(r).bodyJson().extractingPath("$.request.channel").isEqualTo("AI_COPILOT");
    }

    @Test
    @DisplayName("客服只看得到自己提出的補償申請；主管看得到全部")
    void requestVisibility() {
        compensate("alice", 150, null);
        assertThat(get("/api/compensation-requests", "bob")).hasStatus(200).bodyText().isEqualTo("[]");
        assertThat(get("/api/compensation-requests", "carol")).hasStatus(200).bodyText().contains("T-1001");
    }

    /* ---------- helpers ---------- */

    private MvcTestResult compensate(String user, int amount, String externalRef) {
        String ref = externalRef == null ? "" : ",\"externalRef\":\"" + externalRef + "\"";
        return post("/api/tickets/T-1001/compensations", user,
            "{\"type\":\"COUPON\",\"amount\":" + amount + ",\"reason\":\"物流延誤\"" + ref + "}");
    }

    private MvcTestResult get(String url, String token) {
        var req = mvc.get().uri(url);
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req.exchange();
    }

    private MvcTestResult post(String url, String token, String json) {
        var req = mvc.post().uri(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return req.exchange();
    }

    private static String read(MvcTestResult r, String path) {
        String body = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return JsonPath.read(body, path);
    }
}

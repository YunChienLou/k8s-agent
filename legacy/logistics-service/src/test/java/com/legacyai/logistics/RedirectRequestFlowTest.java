package com.legacyai.logistics;

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

/** 改寄申請流程：客服開單 → 物流人員執行或退回。資料在記憶體中，每個測試重建 context。 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedirectRequestFlowTest {

    private static final String NEW_ADDRESS = "台北市信義區松仁路 100 號 12 樓";

    @Autowired MockMvcTester mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean ProfileClient profile;

    @BeforeEach
    void fakeSsoAndProfile() {
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> TestAuth.decode(inv.getArgument(0)));
        given(profile.rolesOf(any(Jwt.class))).willAnswer(inv -> TestAuth.roles(inv.getArgument(0)));
    }

    /* ---------- 認證與權限 ---------- */

    @Test
    @DisplayName("未帶 token → 401")
    void noToken() {
        assertThat(get("/api/shipments/S-3001", null)).hasStatus(401);
    }

    @Test
    @DisplayName("簽章錯誤的 token → 401")
    void badToken() {
        assertThat(get("/api/shipments/S-3001", "bad")).hasStatus(401);
    }

    @Test
    @DisplayName("profile API 回傳沒有角色 → 403")
    void noRoleInThisSystem() {
        assertThat(get("/api/shipments/S-3001", "dave")).hasStatus(403);
    }

    /* ---------- 申請單流程 ---------- */

    @Test
    @DisplayName("客服建立改寄申請：201、狀態待執行，地址尚未變更")
    void createKeepsAddressUnchanged() {
        assertThat(createRequest("alice", "S-3001", null))
            .hasStatus(201)
            .bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(get("/api/shipments/S-3001", "alice")).hasStatus(200).bodyText().contains("文化路");
    }

    @Test
    @DisplayName("同一貨件已有待處理申請 → 409")
    void duplicatePending() {
        assertThat(createRequest("alice", "S-3001", null)).hasStatus(201);
        assertThat(createRequest("alice", "S-3001", null)).hasStatus(409);
    }

    @Test
    @DisplayName("同一個 externalRef 重送 → 200 回傳既有申請單（冪等）")
    void idempotentByExternalRef() {
        String first = idOf(createRequest("alice", "S-3001", "CF-TEST-1"));
        MvcTestResult again = createRequest("alice", "S-3001", "CF-TEST-1");
        assertThat(again).hasStatus(200);
        assertThat(idOf(again)).isEqualTo(first);
    }

    @Test
    @DisplayName("配送中的貨件不能申請改寄 → 409")
    void outForDeliveryCannotRedirect() {
        assertThat(createRequest("alice", "S-3003", null)).hasStatus(409);
    }

    @Test
    @DisplayName("客服不能執行申請單 → 403")
    void agentCannotExecute() {
        String id = idOf(createRequest("alice", "S-3001", null));
        assertThat(post("/api/redirect-requests/" + id + "/execute", "alice", null)).hasStatus(403);
    }

    @Test
    @DisplayName("物流人員執行 → 地址才變更；重複執行 → 409")
    void staffExecutes() {
        String id = idOf(createRequest("alice", "S-3001", null));
        assertThat(post("/api/redirect-requests/" + id + "/execute", "wang", null))
            .hasStatus(200)
            .bodyJson().extractingPath("$.status").isEqualTo("EXECUTED");
        assertThat(get("/api/shipments/S-3001", "alice")).hasStatus(200).bodyText().contains("松仁路");
        assertThat(post("/api/redirect-requests/" + id + "/execute", "wang", null)).hasStatus(409);
    }

    @Test
    @DisplayName("退回必須填原因（400），填寫後退回成功")
    void staffRejects() {
        String id = idOf(createRequest("alice", "S-3001", null));
        assertThat(post("/api/redirect-requests/" + id + "/reject", "wang", "{\"reason\":\"\"}")).hasStatus(400);
        assertThat(post("/api/redirect-requests/" + id + "/reject", "wang", "{\"reason\":\"貨件已交配送，無法攔截\"}"))
            .hasStatus(200)
            .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(get("/api/shipments/S-3001", "alice")).bodyText().contains("文化路");
    }

    /* ---------- 經由 Copilot（ADR-0009） ---------- */

    @Test
    @DisplayName("經由 Copilot 開單：申請人仍是 alice，並記錄 azp=copilot-web")
    void viaCopilotRecordsClient() {
        MvcTestResult r = post("/api/redirect-requests", "alice~copilot-web",
            "{\"shipmentId\":\"S-3001\",\"newAddress\":\"" + NEW_ADDRESS + "\",\"channel\":\"AI_COPILOT\"}");
        assertThat(r).hasStatus(201);
        assertThat(r).bodyJson().extractingPath("$.requestedBy").isEqualTo("alice");
        assertThat(r).bodyJson().extractingPath("$.submittedVia").isEqualTo("copilot-web");
        assertThat(r).bodyJson().extractingPath("$.channel").isEqualTo("AI_COPILOT");
    }

    @Test
    @DisplayName("客服只看得到自己提出的申請；物流人員看得到全部")
    void visibility() {
        createRequest("alice", "S-3001", null);
        assertThat(get("/api/redirect-requests", "bob")).hasStatus(200).bodyText().isEqualTo("[]");
        assertThat(get("/api/redirect-requests", "wang")).hasStatus(200).bodyText().contains("S-3001");
    }

    /* ---------- helpers ---------- */

    private MvcTestResult createRequest(String user, String shipmentId, String externalRef) {
        String ref = externalRef == null ? "" : ",\"externalRef\":\"" + externalRef + "\"";
        return post("/api/redirect-requests", user,
            "{\"shipmentId\":\"" + shipmentId + "\",\"newAddress\":\"" + NEW_ADDRESS + "\",\"reason\":\"客人出差\"" + ref + "}");
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

    private static String idOf(MvcTestResult r) {
        String body = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.id");
    }
}

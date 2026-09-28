package com.legacyai.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Adapter 端到端測試：MCP 請求 → JWT 驗證 → tool → token relay → 舊系統（{@link LegacyOrderStub}）。
 * <p>
 * 對應 M1 的手動驗證（curl／MCP Inspector）與 ADR-0010 的協定測試。
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpAdapterTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void legacySystem(DynamicPropertyRegistry registry) {
        registry.add("legacy.order.base-url", LegacyOrderStub::baseUrl);
    }

    @BeforeEach
    void fakeSso() {
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> TestAuth.decode(inv.getArgument(0)));
        LegacyOrderStub.reset();
    }

    // ── 身分驗證 ──────────────────────────────────────────

    @Test
    @DisplayName("未帶 token → 401；簽章錯誤 → 401")
    void unauthenticated() {
        assertThat(rpc(null, callOrder(1, "O-2001"))).hasStatus(401);
        assertThat(rpc("bad", callOrder(1, "O-2001"))).hasStatus(401);
        assertThat(LegacyOrderStub.lastAuthorization()).as("未通過驗證不應呼叫舊系統").isNull();
    }

    // ── 協定（ADR-0010）────────────────────────────────────

    @Test
    @DisplayName("舊版握手：initialize 成功")
    void legacyHandshake() {
        String initialize = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18","capabilities":{},
              "clientInfo":{"name":"test","version":"1.0"}}}
            """;
        assertThat(rpc("alice", initialize))
            .hasStatus(200)
            .bodyJson().extractingPath("$.result.serverInfo.name").isEqualTo("legacy-adapter");
    }

    @Test
    @DisplayName("新版探測：server/discover 回 JSON-RPC -32601，讓 client 退回 initialize（SDK 升級後改為成功）")
    void modernDiscoverFallsBack() {
        String discover = """
            {"jsonrpc":"2.0","id":"probe-1","method":"server/discover","params":{}}
            """;
        MvcTestResult result = rpc("alice", discover);
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo(-32601);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo("probe-1");
    }

    @Test
    @DisplayName("tools/list 列出 get_order_status")
    void listTools() {
        String list = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """;
        assertThat(rpc("alice", list))
            .hasStatus(200)
            .bodyJson().extractingPath("$.result.tools[0].name").isEqualTo("get_order_status");
    }

    // ── get_order_status（M1）──────────────────────────────

    @Test
    @DisplayName("alice 查 O-2001：token 原封不動轉給舊系統，回傳裁切後的欄位")
    void relaysTokenAndTrimsFields() {
        MvcTestResult result = rpc("alice", callOrder(1, "O-2001"));

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.result.isError").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.result.content[0].text").asString()
            .contains("SHIPPED")
            .contains("S-3001")
            .contains("藍色輕量羽絨外套 M x1")
            .doesNotContain("板橋")      // 收件地址不外流給 Agent
            .doesNotContain("C-001");    // 客戶 ID 不外流給 Agent

        assertThat(LegacyOrderStub.lastAuthorization()).isEqualTo("Bearer alice");
    }

    @Test
    @DisplayName("查無訂單：舊系統 404 → isError「查無訂單 O-9999」")
    void notFound() {
        MvcTestResult result = rpc("alice", callOrder(2, "O-9999"));

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.result.isError").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.result.content[0].text").isEqualTo("查無訂單 O-9999");
    }

    @Test
    @DisplayName("dave 在訂單系統沒有角色：舊系統 403 → isError，權限由舊系統判斷")
    void forbiddenDecidedByLegacy() {
        MvcTestResult result = rpc("dave", callOrder(3, "O-2001"));

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.result.isError").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.result.content[0].text").asString()
            .contains("沒有查詢");
        assertThat(LegacyOrderStub.lastAuthorization())
            .as("adapter 不自行判斷權限，仍把 dave 的 token 轉給舊系統")
            .isEqualTo("Bearer dave");
    }

    // ── helpers ───────────────────────────────────────────

    private MvcTestResult rpc(String token, String json) {
        var req = mvc.post().uri("/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .content(json);
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return req.exchange();
    }

    private static String callOrder(int id, String orderId) {
        return """
            {"jsonrpc":"2.0","id":%d,"method":"tools/call",
             "params":{"name":"get_order_status","arguments":{"orderId":"%s"}}}
            """.formatted(id, orderId);
    }
}

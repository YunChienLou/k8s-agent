# adapter（M1–M2）

Java adapter pod：讀 `tools.yaml`，把舊系統 API 轉成任務型 MCP tool。

M1 spike 目標：先用寫死的一支 tool，驗證 **MCP 請求的 `Authorization` header 能進入 SecurityContext 並轉發給舊系統**。

## 目前進度（M1 spike）

- **Spring AI 2.0.1 MCP server**（`spring-ai-starter-mcp-server-webmvc`），Stateless Streamable HTTP，端點 `POST /mcp`，port **8090**
- 一支寫死的 tool：`get_order_status(orderId)` → 呼叫 order-service `GET /api/orders/{id}`，裁成精簡欄位回傳
- **Token relay**：`SecurityConfig` 驗證 SSO JWT（只驗「你是誰」）→ `TokenRelay` 把同一張 token 轉給舊系統 → 舊系統自己查 profile API 判斷權限（ADR-0009）
- 舊系統的 401／403／404 轉成 Agent 可以直接轉述的錯誤訊息（MCP `isError=true`）

- `DiscoverFallbackFilter`：對 `server/discover` 回 -32601，讓 2026-07-28 版 client（如 Inspector v2）以 Auto 模式退回 `initialize`；MCP Inspector 的協定設定請選 **Auto**（ADR-0010）

### 為什麼 SecurityContext 拿得到

Spring AI 在 servlet 環境會讓 MCP server 以 `immediateExecution` 執行 tool，tool 與 Spring Security filter 跑在同一條 request thread，所以 `SecurityContextHolder` 可以直接取得使用者的 JWT。若改成 WebFlux 或 ASYNC 模式，這個前提就不成立，需改從 `McpTransportContext` 傳遞。

## 本機驗證

```bash
# 1. 啟動 Keycloak、profile-service、order-service（或 docker compose up）
# 2. 啟動 adapter
cd adapter && mvn spring-boot:run

# 3. 取得 alice 的 token，直接放進剪貼簿（從 terminal 畫面複製會把自動換行變成空白 → 401 malformed）
./scripts/token.sh alice copilot-web | tr -d '\r\n' | clip
# 4. 用 MCP Inspector 連線
npx @modelcontextprotocol/inspector
#    Transport: Streamable HTTP
#    URL:       http://localhost:8090/mcp
#    Header:    Authorization: Bearer <剪貼簿內容>
#    協定設定： Auto（ADR-0010）
#    token 效期 15 分鐘，過期會回 401 並觸發 Inspector 的 OAuth 流程
```

預期結果：

| 情境 | 結果 |
|---|---|
| 不帶 token | HTTP 401 |
| alice 查 `O-2001` | 回傳狀態 `SHIPPED`、物流單號 `S-3001` |
| 查 `O-9999` | `isError=true`：「查無訂單 O-9999」 |
| 在訂單系統沒有角色的人 | `isError=true`：「你在訂單系統沒有查詢…的權限」 |

# ADR-0010：MCP 協定版本過渡——現行走 `initialize`，以協商銜接 2026-07-28

- 狀態：已採納
- 日期：2026-09-28

## 背景

- MCP 於 **2026-07-28** 發布 stateless 協定：以 `server/discover` 取代 `initialize`／`initialized` 握手
- Adapter 使用 **Spring AI 2.0.x**，底層為 **MCP Java SDK 2.0**，**尚未支援 2026-07-28**；維護者表示預計 **SDK 2.2** 支援（[spring-ai#6954](https://github.com/spring-projects/spring-ai/issues/6954)）
- SDK 2.0 收到 `server/discover` 時回 **HTTP 500**（[java-sdk#1072](https://github.com/modelcontextprotocol/java-sdk/issues/1072)），新版 client 因此無法退回 `initialize`，直接連線失敗
- 實際踩到：MCP Inspector v2.8 連線 adapter 時出現 `Missing handler for request type: server/discover`

## 決策

### 1. 現行協定：`initialize` 握手（舊版）

- Adapter 維持 Spring AI 2.0.x，不為了新協定改用其他語言或自行實作協定層
- 舊版協定經由**版本協商**仍屬規格內的正常行為，不是權宜之計

### 2. Server 端：依 2026-07-28 規格回應「不支援」

- 新增 `DiscoverFallbackFilter`：攔截 `server/discover`，回 **HTTP 200 + JSON-RPC -32601（Method not found）**（已以 MCP Inspector v2.8 實測可 fallback）
- 與 SDK 主線的修正行為一致，讓 Auto 模式的 client 能正確退回 `initialize`
- 其他請求原封不動交給 MCP server

### 3. Client 端：Auto 模式，不固定版本

| 模式 | 行為 | 採用 |
|---|---|---|
| Legacy／固定舊版號 | 只走 `initialize` | ✗ 把自己鎖在舊版 |
| **Auto** | 先試 `server/discover`，不支援才退回 `initialize` | ✓ |
| Modern（pin 2026-07-28） | 只走新版，不 fallback | ✗ server 支援前必定失敗 |

- **Production 的 client 只有自家 Agent**（NetworkPolicy，見 ADR-0009），協定版本由我們在 Agent 端控制
- 開發工具（MCP Inspector）同樣設為 Auto

## 升級路徑

1. 追蹤 SDK 2.2 發版與 stateless lifecycle 進度（[java-sdk#1011](https://github.com/modelcontextprotocol/java-sdk/issues/1011)）
2. 升級 Spring AI／MCP SDK
3. 刪除 `DiscoverFallbackFilter`
4. 協定整合測試改為「兩種握手都成功」並通過
5. K8s 滾動更新；client 為 Auto，新舊 pod 並存期間不斷線
6. 確認穩定後，Agent 可視需要改為固定 Modern

## 驗證方式

- 整合測試（`adapter/src/test/.../McpAdapterTest`）分別以 `initialize` 與 `server/discover` 呼叫 `POST /mcp`
  - **現行預期**：`initialize` 成功；`server/discover` 回 -32601
  - **升級後預期**：兩者皆成功
- 升級是否完成由 CI 判斷，不靠人工確認

## 取捨

- 過渡期拿不到 2026-07-28 的新能力（例如免握手的 stateless 呼叫）；adapter 已採 Stateless Streamable HTTP，對水平擴展沒有影響
- 只講 2026-07-28 的外部 client（例如固定 Modern 的第三方平台）在 SDK 升級前無法接入；目前架構不開放外部 client，列入已知限制
- `DiscoverFallbackFilter` 需讀取並重放 request body，每個 MCP 請求多一次 JSON 解析；成本相對 LLM 與舊系統呼叫可忽略

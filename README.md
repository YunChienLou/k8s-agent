# legacy-ai-bridge

> 讓傳統企業系統**一行程式都不用改**，就能被 AI Agent 安全地使用。

大部分企業的核心系統（Spring Boot、Oracle、公司 SSO）不會因為 AI 而重寫。
這個專案示範另一條路：**在 K8s 旁邊加一個 adapter pod**，把舊系統的 API 轉成 Agent 能用的 tool，
並且把企業最在意的三件事做好：

- **權限繼承**：Agent 帶的是「使用者本人」的 JWT，舊系統原本的權限規則照樣生效
- **人確認意圖、舊系統決定**：查詢自動執行；寫入動作先由使用者在「行動卡片」確認，再到舊系統**提單**，由舊系統既有的人員與規則決定能否執行
- **稽核追蹤**：誰、在什麼時候、透過 Agent 呼叫了什麼、依據是什麼

## Demo 情境：客服 Copilot

客服 alice 收到工單：「上週買的藍色外套還沒到，下週要出差，能改寄公司嗎？運費能補償嗎？」

| 傳統做法 | Copilot |
|---|---|
| 開工單系統 → 開訂單系統查訂單 → 開物流系統查貨態 → 判斷能否改寄 → 物流系統改地址 → 工單系統發補償 → 回覆客人 | **一句話** → Agent 跨 3 個系統查證並提出方案 → alice **確認一次** → 舊系統出現申請單，由負責人員執行 |
| 6 個畫面、自己判斷 | 判斷依據全部列出，可追溯 |

完整情境與驗收條件見 [docs/scenarios.md](docs/scenarios.md)。

## 架構

```
 Vue3 Copilot ──► Agent (FastAPI + LangGraph) ──MCP──► Adapter pods (Java) ──REST──► 舊系統
      │                  │  interrupt() 人工確認          │  tools.yaml 定義任務型 tool      │
      └──── JWT（公司 SSO：Keycloak）一路傳到底 ───────────┴──────────────────────────────┘
```

- **Adapter pod**：同一個 image，每個舊系統配一份 `tools.yaml`（ConfigMap）。組合多支 API、裁切欄位、標註風險等級
- **Agent**：Python + LangGraph，透過 `langchain-mcp-adapters` 呼叫 adapter
- **確認 ≠ 授權**：使用者確認的是意圖；能不能執行由舊系統判斷。Adapter 另外檢查「使用者確實確認過」，就算 Agent 被繞過也無法提單

設計決策見 [docs/adr/](docs/adr/)。

## 目錄

```
infra/            docker-compose、Keycloak realm（模擬公司 SSO）
legacy/           三個「舊系統」：訂單、物流、客服工單（純 Spring Boot，不含任何 AI 套件）
adapter/          Java adapter pod（M1）
agent/            Python Agent（M3）
web/              Vue3 Copilot（M4）
scripts/          取 token、smoke test、.http 範例
docs/             情境、ADR
```

## 快速開始

需求：Docker Desktop。（本機開發另需 JDK 21、Maven 3.9）

```bash
cd infra
docker compose up -d --build        # 第一次會下載 Maven 依賴，約 3–5 分鐘
cd ..
./scripts/smoke-test.sh             # 預期 11 項全部 PASS
```

Windows 可用 Git Bash 執行腳本，或用 IDE 開 `scripts/requests.http`。

| 服務 | URL |
|---|---|
| Keycloak（admin / admin） | http://localhost:8080 |
| 訂單系統 Swagger | http://localhost:8081/swagger-ui.html |
| 物流系統 Swagger | http://localhost:8082/swagger-ui.html |
| 客服工單系統 Swagger | http://localhost:8083/swagger-ui.html |

### 測試帳號（密碼同帳號）

| 帳號 | 角色 | 權限重點 |
|---|---|---|
| alice | cs_agent | 只能處理自己的工單（T-1001、T-1003）；補償上限 100 元 |
| bob | cs_agent | 只能處理自己的工單（T-1002）；補償上限 100 元 |
| carol | cs_supervisor | 可看全部工單；補償上限 500 元 |
| dave | sre | 看不到客服資料（預留給 AIOps） |

## Roadmap

- [x] **M0** 舊系統 + SSO：三個假系統、Keycloak、JWT 權限規則、smoke test
- [ ] **M0.5** 舊系統改為申請單流程（改寄申請、補償簽核）+ 舊系統 Vue 前端（審核頁、待簽核頁）（ADR-0003）
- [ ] **M1** Adapter spike：MCP tool 帶 JWT 呼叫舊系統（用 MCP Inspector 驗證）
- [ ] **M2** Adapter 改為 `tools.yaml` 驅動：多步驟組合、JSONPath 裁切、稽核 log
- [ ] **M3** Agent：LangGraph 跨系統查詢 + `interrupt()` 人工確認
- [ ] **M4** Copilot UI：步驟追蹤 + 可編輯的行動卡片
- [ ] **M5** 收尾：情境篩選 tool、固定測試案例、demo GIF
- [ ] **M6** AIOps：告警觸發 Agent 調查物流服務異常並建議 rollback（k3d）

## 已知限制（真實企業會遇到的）

- **各系統自行簽發 token**：本 demo 假設公司 SSO 發的 OIDC token 各系統都認。若舊系統登入後另發自己的 session／JWT，需要 **token exchange（RFC 8693）**
- **舊系統沒有 OpenAPI spec**：本 demo 用 springdoc 自動產生；真實情況常需手寫
- **確認延遲與 token 過期**：確認可能等上數小時，確認時必須使用當下有效的 token 提單

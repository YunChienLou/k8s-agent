# k8s-agent

> 讓傳統企業系統**一行程式都不用改**，就能被 AI Agent 安全地使用。

大部分企業的核心系統（Spring Boot、Oracle、公司 SSO）不會因為 AI 而重寫。
這個專案示範另一條路：**在 K8s 旁邊加一個 adapter pod**，把舊系統的 API 轉成 Agent 能用的 tool，
並且把企業最在意的三件事做好：

- **權限繼承**：SSO token 一路傳到舊系統，舊系統照原本的方式用公司 profile API 判斷權限；經由 Copilot 的動作以 token 的 `azp` 記錄（ADR-0009）
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
                         Keycloak (SSO)          profile API
                              │ token                 ▲
                              ▼                       │ roles?system=xxx
  Vue3 Copilot ──► Agent (FastAPI + LangGraph)        │
                     │  interrupt()                   │
                     │ MCP (one endpoint per domain)  │
        ┌────────────┴─────────────┐                  │
        ▼                          ▼                  │
┌─ ns: domain-cs ──────────────┐ ┌─ ns: domain-finance (sample) ─┐
│ Deployment: adapter-cs  x2   │ │ Deployment: adapter-fin  x2   │
│ ┌──────────────────────────┐ │ │ ┌───────────────────────────┐ │
│ │ ConfigMap tools/         │ │ │ │ ConfigMap tools/          │ │
│ │  order.yaml              │ │ │ │  billing.yaml             │ │
│ │  logistics.yaml          │ │ │ │  invoice.yaml             │ │
│ │  ticket.yaml             │ │ │ └───────────────────────────┘ │
│ └──────────────────────────┘ │ │ bulkhead: [billing][invoice]  │
│ bulkhead + circuit breaker   │ └──────────────┬────────────────┘
│  [order][logistics][ticket]  │                │
└──────┬─────────┬─────────┬───┘                ▼
       │ REST    │         │               billing / invoice
       ▼         ▼         ▼
    order    logistics   ticket  ── each legacy asks profile API
   service    service    service
   ◄── NetworkPolicy: adapter-cs 只能連客服域的系統 ──►
```

- **Adapter pod**：同一個 image，**依業務域分組部署**（K8s namespace 為邊界），讀取 `tools/` 目錄下每個舊系統一份 `tools.yaml`（ConfigMap）；各舊系統以 bulkhead／circuit breaker 隔離故障（ADR-0011）。組合多支 API、裁切欄位、標註風險等級
- **Agent**：Python + LangGraph，透過 `langchain-mcp-adapters` 呼叫 adapter
- **LLM 可切換**：開發與 demo 用雲端 API，正式環境一律地端（vLLM），只改環境變數；embedding 從 demo 起即地端；地端模型須通過評測集才能上線
- **RAG 對齊規章**：寫入類建議必先查規章並附引用出處，引用由程式驗證（ADR-0004）；規章從既有知識庫同步，發布即生效、可回滾（ADR-0005）
- **確認 ≠ 授權**：使用者確認的是意圖；能不能執行由舊系統判斷。Adapter 另外檢查「使用者確實確認過」，就算 Agent 被繞過也無法提單

設計決策見 [docs/adr/](docs/adr/)。

## Build vs Buy

市面 MCP gateway 解決的是「Agent 能不能安全地呼叫 API」；本專案要解決的是「Agent 怎麼照公司原本的規矩把事情辦完」。

| 層 | 內容 | 策略 |
|---|---|---|
| 門禁層 | 驗證、token 傳遞、tool 白名單、稽核 | 依標準介面自建，可替換為現成 gateway |
| Tool 定義層 | `tools.yaml` 任務型 tool | 宣告式，替換時轉格式 |
| 辦事流程層 | 確認卡片、送進舊系統簽核、規章 RAG、Saga | 自建，本專案核心 |

判斷表與理由見 [ADR-0008](docs/adr/0008-layering-and-build-vs-buy.md)。

## 目錄

```
infra/            docker-compose、Keycloak realm（模擬公司 SSO）
legacy/           「舊系統」：訂單、物流、工單、profile API（Spring Boot）與物流、工單的 Vue 前端，皆不含任何 AI 套件
adapter/          Java adapter pod（M1）
agent/            Python Agent（M3）
web/              Vue3 Copilot（M4）
scripts/          取 token、smoke test、.http 範例
docs/             情境、ADR
```

## 技術版本

| 範圍 | 版本 |
|---|---|
| Java 後端（舊系統、profile API、Adapter） | **Java 21**、**Spring Boot 4.1.1**、springdoc-openapi 3.1.1 |
| 前端（舊系統 Vue、Copilot） | **Node 22**（`.nvmrc`）、**Vue 3**、**Vite 8** |
| Agent | Python（uv）、LangGraph |
| SSO | Keycloak 26 |

Spring Boot 4 的 starter 名稱與 3.x 不同：`spring-boot-starter-webmvc`、`spring-boot-starter-security-oauth2-resource-server`。

## 快速開始

需求：Docker Desktop。（本機開發另需 JDK 21、Maven 3.9；前端需 Node 22.12 以上，Vite 8 的最低需求）

```bash
cd infra
docker compose up -d --build        # 第一次會下載 Maven 依賴，約 3–5 分鐘
cd ..
./scripts/smoke-test.sh             # 端對端：只測需要真正 Keycloak 的部分（6 項）
```

Windows 可用 Git Bash 執行腳本，或用 IDE 開 `scripts/requests.http`。

| 服務 | URL |
|---|---|
| Keycloak（admin / admin） | http://localhost:8080 |
| 訂單系統 Swagger | http://localhost:8081/swagger-ui.html |
| 物流系統 Swagger | http://localhost:8082/swagger-ui.html |
| 客服工單系統 Swagger | http://localhost:8083/swagger-ui.html |
| **物流系統前端（舊）**：改寄申請審核 | http://localhost:5175（用 `wang` 登入） |
| **工單系統前端（舊）**：補償簽核 | http://localhost:5176（用 `carol` 登入） |

### Demo：切到舊系統畫面看申請單出現

Copilot 完成前，先用腳本模擬「alice 在 Copilot 按下確認」：

1. 瀏覽器開 http://localhost:5175，用 `wang`／`wang` 登入物流系統
2. 執行 `./scripts/demo-submit.sh redirect`
3. 約 5 秒內，畫面出現一筆**新的**改寄申請，來源管道標示 **AI Copilot**、經由 `copilot-web`
4. 按「執行」後，貨件地址才真正變更；按「退回」需填寫原因

補償簽核同理：http://localhost:5176 用 `carol` 登入，執行 `./scripts/demo-submit.sh comp`。

前端透過 nginx（開發時為 Vite proxy）反向代理 `/api`，與後端同源，**舊系統後端不需要開 CORS**。

### 權限怎麼判斷

比照企業現況（ADR-0009）：

- **SSO（Keycloak）只負責「你是誰」**，同一張 token 在各系統之間通用
- **profile API（port 8084）負責「你在各系統能做什麼」**，舊系統拿同一張 token 去查本系統的角色，結果快取 60 秒
- 從哪個前端登入都可以：`order-web`／`logistics-web`／`ticket-web`（舊系統前端）或 `copilot-web`（Copilot）。token 的 `azp` 會記錄是哪一個，經由 Copilot 送出的申請單因此可以辨識

`./scripts/token.sh <帳號> <client>` 取得 token。

### 測試帳號（密碼同帳號）

| 帳號 | 角色 | 權限重點 |
|---|---|---|
| alice | cs_agent（訂單、物流、工單） | 只能處理自己的工單（T-1001、T-1003）；補償上限 100 元 |
| bob | cs_agent | 只能處理自己的工單（T-1002）；補償上限 100 元 |
| carol | cs_supervisor | 可看全部工單；補償上限 500 元；簽核補償申請 |
| wang | logistics_staff（僅物流） | 在物流系統執行或退回改寄申請 |
| dave | sre（僅維運） | 在三個客服相關系統都沒有角色（預留給 AIOps） |

## 測試

| 層級 | 內容 | 怎麼跑 |
|---|---|---|
| **單元／整合測試（JUnit）** | 各服務的業務規則：權限、補償上限與簽核、改寄申請流程、冪等、經由 Copilot 的紀錄、profile API fail closed | 各服務目錄下 `mvn test`，或根目錄 `mvn test` 一次跑完 |
| **端對端 smoke test** | 需要真正 Keycloak 的部分：token 簽發、同一張 SSO token 跨系統、真實 token 的 `azp` | `docker compose up` 後執行 `./scripts/smoke-test.sh` |
| **CI** | GitHub Actions：4 個後端服務 `mvn test`、2 個前端 typecheck + build | push／PR 自動執行（`.github/workflows/test.yml`） |

JUnit 測試以 mock 取代 SSO（`JwtDecoder`）與 profile API（`ProfileClient`），請求仍經過真正的 Spring Security 過濾器與角色轉換，**不需要 Docker 或 Keycloak**。

本機沒有 Maven 時，可以用 Docker 跑（PowerShell，在專案根目錄）：

```powershell
docker run --rm -v ${PWD}:/src -v maven-repo:/root/.m2 -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

## Roadmap

- [x] **M0** 舊系統 + SSO：三個假系統、Keycloak、JWT 權限規則、smoke test
- [x] **M0.5a** 舊系統後端改為申請單流程（改寄申請、補償簽核）、權限改由公司 profile API 判斷（ADR-0003、0009）
- [x] **M0.5b** 舊系統 Vue 前端（Vue 3、Vite 8）：物流改寄審核頁、工單補償待簽核頁、`demo-submit.sh`
- [x] **M1** Adapter spike：MCP tool 帶 JWT 呼叫舊系統（Spring AI MCP server + token relay；以 curl／MCP Inspector 驗證，ADR-0010）
- [ ] **M2** Adapter 改為 `tools.yaml` 驅動：讀取 `tools/` 目錄多份 yaml、依業務域部署 `adapter-cs`、每系統 bulkhead／circuit breaker、多步驟組合、JSONPath 裁切、稽核 log（ADR-0011）
- [ ] **M3** Agent：LangGraph 跨系統查詢 + `interrupt()` 人工確認
- [ ] **M3.5** 規章 RAG：補償辦法／客服 SOP 知識庫、強制查詢節點、引用驗證
- [ ] **M4** Copilot UI：步驟追蹤 + 可編輯的行動卡片
- [ ] **M5** 收尾：情境篩選 tool、固定測試案例、demo GIF
- [ ] **M6** AIOps：告警觸發 Agent 調查物流服務異常並建議 rollback（k3d）

## 已知限制（真實企業會遇到的）

- **各系統自行簽發 token**：本 demo 假設公司 SSO 發的 OIDC token 各系統都認。若舊系統登入後另發自己的 session／JWT，需要 **token exchange（RFC 8693）**
- **舊系統沒有 OpenAPI spec**：本 demo 用 springdoc 自動產生；真實情況常需手寫
- **SSO token 外洩的影響範圍**：預設 passthrough，token 在效期內可呼叫所有系統；以「只在記憶體傳遞、NetworkPolicy、短效期」降低風險。要求更高的環境可改用逐段 token exchange（ADR-0007、0009）
- **跨系統無法原子化**：多個舊系統各自審核，可能部分完成；以 Saga（依序提單、人工決定補償、逾時提醒）處理，無法憑空創造原子性（ADR-0006）
- **MCP 協定版本過渡**：Java SDK 2.0 尚未支援 2026-07-28（`server/discover`），adapter 暫走 `initialize` 握手，client 需用 Auto 模式；只講新版協定的外部 client 須等 SDK 2.2（ADR-0010）
- **確認延遲與 token 過期**：確認可能等上數小時，確認時必須使用當下有效的 token 提單

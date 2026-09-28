# ADR-0009：預設採 SSO token passthrough＋公司 profile API 判斷權限

- 狀態：已採納（取代 [ADR-0007](0007-per-hop-token-exchange.md) 成為預設做法；ADR-0007 改為選配的強化模式）
- 日期：2026-09-28

## 背景

ADR-0007 以逐段 token exchange 縮小 token 外洩時的影響範圍，但與多數企業的現況不符：

- 企業導入 SSO 的目的就是讓**同一張 token 在各系統之間通用**
- 各系統收到 token 後，拿它去問**公司的 profile API**「這個人在本系統有哪些角色」，權限判斷本來就不依賴 token 的 audience
- 要求舊系統驗證 `aud`、要求 SSO 支援 RFC 8693，都違反「舊系統零改動」，而且許多既有 SSO 不支援 token exchange

## 決策

### 1. 權限模型（比照企業現況）

- **SSO（demo：Keycloak）只負責「你是誰」**：簽發 JWT，不帶系統角色
- **profile API 負責「你在各系統能做什麼」**：全公司共用一支，`GET /api/me` 回傳使用者在每個系統的角色，`GET /api/me/roles?system=<系統>` 回傳單一系統的角色
- 舊系統驗證 token 的簽章與 issuer，再用**同一張 token** 查 profile API，轉成本系統的角色
- 同一張 SSO token 可以呼叫任何系統；能不能做事，由 profile API 與各系統自己的規則決定

### 2. Copilot 的 token 流向

```
alice 從 Copilot 登入（copilot-web）→ SSO token
Copilot ──token──▶ Agent ──token──▶ Adapter ──token──▶ 舊系統 ──token──▶ profile API
```

舊系統看到的就是 alice 本人，權限規則完全沿用，**舊系統零改動**。

### 3. 分辨「經由 AI」：用 token 本身的 `azp`

- 使用者從 Copilot 登入時，token 的 `azp`（授權的 client）是 `copilot-web`；從舊系統前端登入時是該系統的 web client
- `azp` 在簽章範圍內，**無法偽造**。舊系統可以記錄「這個動作經由 Copilot 送出」（demo：申請單的 `submittedVia` 欄位）
- 若日後要限制「經由 Copilot 只能開單、不能核准」，舊系統可依 `azp` 判斷；這屬於舊系統的程式修改，列為選配
- 修正先前說法：passthrough 並非完全無法分辨經由 AI，差別在於 token exchange 的代理身分（`act`）更明確

### 4. 補償措施（passthrough 的剩餘風險）

同一張 token 會經過 Agent 與 Adapter，而 Agent 會處理客人、工單等**不可信文字**。以下措施降低外洩風險：

- Token **只在 HTTP header 與記憶體中傳遞**：不進 prompt、不當 tool 參數、不寫 log、不寫資料庫、不寫 LangGraph checkpoint
- 稽核只記錄 claim（`sub`、`preferred_username`、`azp`），不記錄 token
- **NetworkPolicy**：每個 Adapter 只能連到同一業務域的舊系統（ADR-0011）；Agent 只能連 Adapter 與 LLM 端點
- Agent **只能使用 MCP tool**，不提供 shell、程式執行或任意對外連線（ADR-0008）
- 縮短 SSO token 效期，確認時由前端帶上當下有效的 token（沿用 ADR-0002 的規則）
- 申請單標記來源管道 `AI_COPILOT`，並記錄 `azp`

### 5. 效能：角色查詢快取

- 每個請求都查 profile API 會讓它成為瓶頸；Agent 一句話又會放大成多次呼叫
- 舊系統以（使用者, 系統）為鍵快取角色 60 秒
- **取捨**：角色被撤銷後，最多 60 秒才生效
- profile API 無法連線時**視為沒有角色（fail closed → 403）**，不放行

## 何時改用 ADR-0007（強化模式）

- 金融、保險、醫療等要求 token 外洩影響範圍最小化的環境
- 公司 SSO 支援 RFC 8693，且各舊系統可以加上 `aud` 驗證設定
- 需要在 token 中明確標示代理身分（`act` claim）

## 取捨

- Token 外洩時，攻擊者在效期內可用使用者身分呼叫所有系統；以上述補償措施與短效期降低風險，並列入風險清單
- 權限判斷依賴 profile API 的可用性；以快取與 fail closed 處理
- **與 MCP 規範的安全建議不一致**：規範將「MCP server 把收到的 token 原樣轉給下游 API」列為反模式。這是為了符合企業現況、維持舊系統零改動而做的刻意取捨，需在導入評估時明確告知

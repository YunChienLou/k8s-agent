# ADR-0002：使用者 JWT 一路傳遞（passthrough），不使用 service account

- 狀態：**已被 [ADR-0009](0009-sso-passthrough-with-profile-api.md) 取代**（passthrough 做法保留；權限改由公司 profile API 判斷，並補上補償措施）
- 日期：2026-09-28

## 背景

Agent 呼叫舊系統時，可以用一個高權限的 service account，也可以帶使用者本人的 token。

## 決策

**前端 → Agent → adapter → 舊系統全程傳遞使用者本人的 JWT**（公司 SSO，本 demo 以 Keycloak 模擬）。

## 理由

- 舊系統已有完整的權限規則（本 demo：工單指派、補償上限）。用 service account 等於要在 Agent 端重寫一套，而且一定會漏
- 越權時由舊系統直接回 403，Agent 無法「想辦法繞過」
- 稽核紀錄上的操作人就是真人

## 規則

- Token **只在 HTTP header 傳遞**，絕不進入 prompt，也不能成為 tool 參數（避免 prompt injection 竊取）
- 稽核 log 只記錄 `sub`、`preferred_username` 等 claim，不記錄 token 本身
- 人工確認可能延遲數小時：**確認時由前端帶上當下有效的 token** 提單，不重用發起時的 token

## 取捨

- 前提是各系統都接受同一個 SSO 簽發的 token；若舊系統自行簽發 session／JWT，需另做 token exchange（RFC 8693）
- 背景任務（如 AIOps 告警觸發）沒有「使用者」，需另外定義機器身分與其權限範圍（M6 處理）

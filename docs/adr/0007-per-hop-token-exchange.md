# ADR-0007：逐段 token exchange，每張 token 只給下一站使用

- 狀態：已採納（取代 [ADR-0002](0002-jwt-passthrough.md) 的「原樣轉發」做法）
- 日期：2026-09-28

## 背景

ADR-0002 決定讓使用者的 JWT 從前端一路原樣轉發到舊系統，以繼承舊系統的權限規則。
但同一張 token 在每一段都有效，任何一段外洩，就能拿去呼叫所有系統；
MCP 規範的安全最佳實務也將「MCP server 把收到的 token 原樣轉給下游 API」列為反模式，
並以 RFC 8707 resource indicators 限制 token 只能用於指定的伺服器。

**保留** ADR-0002 的核心目的：舊系統看到的仍然是使用者本人，權限規則照樣生效。
**改變**的是傳遞方式：每一段都換成只給下一站使用的 token。

## 決策

### 1. Token 鏈

```
alice 登入 → T0 (aud=copilot-agent)
Copilot  ──T0──▶ Agent
Agent    ──exchange → T1 (aud=mcp-adapter-logistics)──▶ Adapter
Adapter  ──exchange → T2 (aud=logistics-service, 最小 scope)──▶ 物流系統
```

- 交換使用 **OAuth 2.0 Token Exchange（RFC 8693）**，由公司 SSO（demo：Keycloak）簽發
- 每一次交換後的 token，`sub` 仍是使用者本人，舊系統的權限規則照樣生效

### 2. 每一段都驗 audience

- Agent 只接受 `aud=copilot-agent`；每個 Adapter 只接受 `aud` 為自己的 token
- Adapter 依 MCP 規範作為 OAuth resource server，並提供 Protected Resource Metadata（RFC 9728）
- 收到 audience 不符的 token 一律拒絕

### 3. 交換權限白名單

在 SSO 設定「誰能換成誰的 token」：

| 發起者 | 只能換成 |
|---|---|
| `copilot-agent` | `mcp-adapter-*` |
| `mcp-adapter-logistics` | `logistics-service` |
| `mcp-adapter-ticket` | `ticket-service` |
| `mcp-adapter-order` | `order-service` |

就算某個 Adapter 被攻破，也拿不到其他系統的 token。

### 4. 最小權限與短效期

- 下游 token 只帶該 tool 需要的 scope（例如只能建立改寄申請）
- 有效期約 5 分鐘；交換結果只以（使用者, audience）為鍵暫存在記憶體，到期即丟，不跨使用者共用、不落地

### 5. 代理身分

- 交換後的 token 標記「Copilot 代使用者操作」（`act` claim，RFC 8693），與 ADR-0003 一致
- Keycloak standard token exchange 可換 audience；是否能直接帶出 `act` claim 需於實作時驗證，不行則以 protocol mapper 補上

### 6. 沿用 ADR-0002 的規則

- Token 只在 HTTP header 傳遞，絕不進入 prompt，也不能成為 tool 參數
- 稽核 log 只記錄 claim（`sub`、`preferred_username`、`act`），不記錄 token 本身
- 使用者確認時，前端帶上當下有效的 T0，整條鏈重新交換

## SSO 設定（demo：Keycloak）

- `copilot-web`：public client，前端登入（PKCE）
- `copilot-agent`、`mcp-adapter-order`、`mcp-adapter-logistics`、`mcp-adapter-ticket`：confidential client，啟用 token exchange
- `order-service`、`logistics-service`、`ticket-service`：作為 audience，僅驗證 token

## 取捨與已知限制

- 每次呼叫多一到兩次交換，增加延遲與 SSO 負載；以短效期快取緩解
- **要完整生效，舊系統也需驗證 `aud`**。在 Spring Security 中只需增加 audiences 設定，屬設定變更、不改程式，但仍是對舊系統的一點改動
- 若某個舊系統無法驗證 `aud`：Agent 與 Adapter 兩段仍受保護，但該舊系統可能接受發給其他系統的 token，須列入風險清單
- 背景任務（例如 M6 AIOps）沒有使用者，改用 Agent 自身的 client credentials，並給予獨立、受限的角色

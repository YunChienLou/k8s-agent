# adapter（M1–M2）

Java adapter pod：讀 `tools.yaml`，把舊系統 API 轉成任務型 MCP tool。

M1 spike 目標：先用寫死的一支 tool，驗證 **MCP 請求的 `Authorization` header 能進入 SecurityContext 並轉發給舊系統**。

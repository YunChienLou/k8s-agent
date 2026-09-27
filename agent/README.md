# agent（M3）

Python（uv）+ LangGraph + FastAPI。沿用本機 Agent 專案的 agent core，透過 `langchain-mcp-adapters` 呼叫 adapter pods；寫入類 tool 以 `interrupt()` 等待人工審核。

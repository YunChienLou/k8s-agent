package com.legacyai.adapter;

/**
 * 呼叫舊系統失敗時丟出。訊息會成為 MCP tool 結果（isError=true）的內容，
 * 所以要寫成 Agent 能直接轉述給使用者的句子。
 */
public class LegacyCallException extends RuntimeException {

    public LegacyCallException(String message) {
        super(message);
    }
}

package com.legacyai.adapter;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

/**
 * Token relay：把 MCP 請求帶進來的 SSO token 原封不動轉給舊系統。
 * <p>
 * 能這樣做的前提：在 servlet 環境下 Spring AI 會讓 MCP server 以 immediateExecution 執行 tool，
 * tool 與 Spring Security filter 在同一條 thread，SecurityContextHolder 取得到使用者。
 * <p>
 * Token 只存在 header 與記憶體，不寫 log、不當 tool 參數（ADR-0009 補償措施）。
 */
public class TokenRelay implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
        }
        return execution.execute(request, body);
    }
}

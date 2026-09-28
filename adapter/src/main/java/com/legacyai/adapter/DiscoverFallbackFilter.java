package com.legacyai.adapter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 相容 MCP 2026-07-28 stateless 規格的 client（例如 MCP Inspector v2）。
 * <p>
 * 新版 client 會先送 {@code server/discover}，收到 JSON-RPC -32601（Method not found）才退回
 * {@code initialize} 握手。Spring AI 2.0.1 的 MCP SDK 不認得這個方法，且回的是 HTTP 500，
 * client 因此不會 fallback。這個 filter 攔下 {@code server/discover} 並依規格回 -32601。
 * <p>
 * 行為與 MCP Java SDK 主線的修正一致（java-sdk #1072）；升級到支援 2026-07-28 的 SDK（預計 2.2）後即可移除。
 */
@Component
public class DiscoverFallbackFilter extends OncePerRequestFilter {

    private static final String DISCOVER = "server/discover";

    private final JsonMapper json = JsonMapper.builder().build();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RpcProbe(String method, Object id) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/mcp"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();

        RpcProbe probe = null;
        try {
            probe = json.readValue(body, RpcProbe.class);
        } catch (RuntimeException ignored) {
            // 批次請求或格式錯誤：交給 MCP server 自己處理
        }

        if (probe != null && DISCOVER.equals(probe.method())) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("jsonrpc", "2.0");
            error.put("id", probe.id());
            error.put("error", Map.of("code", -32601, "message", "Method not found: " + DISCOVER));
            // 回 HTTP 200 + JSON-RPC error：已實測 MCP Inspector v2.8 可據此 fallback。
            // 2026-07-28 規格對 HTTP 狀態碼的要求待確認，改動前請先用 Inspector 實測。
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(error));
            return;
        }

        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** body 已被讀過，包一層讓後面的 MCP transport 可以再讀一次。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }
    }
}

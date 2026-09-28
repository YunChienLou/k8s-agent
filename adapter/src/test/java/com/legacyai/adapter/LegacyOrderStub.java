package com.legacyai.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 假的訂單系統：用 JDK 內建 HttpServer 跑在隨機 port，走真正的 HTTP。
 * <p>
 * 行為比照 order-service：沒帶 token → 401；在訂單系統沒有角色 → 403；查無訂單 → 404。
 * 同時記錄收到的 Authorization header，用來驗證 adapter 有把 token 原封不動轉過來。
 */
final class LegacyOrderStub {
    private LegacyOrderStub() {}

    /** profile API 對訂單系統回傳有角色的使用者（cs_agent / cs_supervisor）。 */
    private static final Set<String> ORDER_USERS = Set.of("alice", "bob", "carol");

    private static final String O_2001 = """
        {"id":"O-2001","customerId":"C-001",
         "items":[{"sku":"JK-BLU-M","name":"藍色輕量羽絨外套 M","quantity":1,"unitPrice":2980}],
         "totalAmount":2980,"status":"SHIPPED",
         "shippingAddress":"新北市板橋區文化路一段 100 號 5 樓",
         "shipmentId":"S-3001","createdAt":"2026-09-20T09:36:14"}
        """;

    private static final HttpServer SERVER;
    private static volatile String lastAuthorization;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.createContext("/api/orders/", LegacyOrderStub::handle);
        SERVER.start();
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    static String lastAuthorization() {
        return lastAuthorization;
    }

    static void reset() {
        lastAuthorization = null;
    }

    private static void handle(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        lastAuthorization = auth;

        if (auth == null || !auth.startsWith("Bearer ")) {
            reply(ex, 401, "");
            return;
        }
        String user = auth.substring("Bearer ".length()).split("~", 2)[0];
        if (!ORDER_USERS.contains(user)) {
            reply(ex, 403, "");
            return;
        }
        String id = ex.getRequestURI().getPath().substring("/api/orders/".length());
        if ("O-2001".equals(id)) {
            reply(ex, 200, O_2001);
        } else {
            reply(ex, 404, "");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}

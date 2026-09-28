package com.legacyai.adapter.order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import com.legacyai.adapter.LegacyCallException;
import com.legacyai.adapter.TokenRelay;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 訂單系統的 MCP tools（M1：寫死一支，驗證 token 能一路轉到舊系統）。
 * <p>
 * Adapter 的職責：把舊系統「細碎、回傳完整資料」的 API，轉成 Agent 好用的任務型 tool，
 * 並裁掉 Agent 不需要的欄位（例如完整地址），降低 token 用量與個資外流。
 */
@Component
public class OrderTools {

    private final RestClient order;

    public OrderTools(RestClient.Builder builder, @Value("${legacy.order.base-url}") String baseUrl) {
        this.order = builder
            .baseUrl(baseUrl)
            .requestInterceptor(new TokenRelay())
            .build();
    }

    @Tool(name = "get_order_status",
          description = "依訂單編號查詢訂單狀態、購買商品、金額、下單時間與物流單號。"
                      + "物流單號可再用於查詢配送進度。")
    public OrderStatus getOrderStatus(
            @ToolParam(description = "訂單編號，格式如 O-2001") String orderId) {
        LegacyOrder o = call("訂單 " + orderId,
            () -> order.get().uri("/api/orders/{id}", orderId).retrieve().body(LegacyOrder.class));
        return new OrderStatus(
            o.id(), o.status(),
            o.items().stream().map(i -> i.name() + " x" + i.quantity()).toList(),
            o.totalAmount(), o.createdAt(), o.shipmentId());
    }

    /** 把舊系統的 HTTP 錯誤轉成 Agent 可以直接轉述的訊息。 */
    private static <T> T call(String what, Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                throw new LegacyCallException("訂單系統沒有回傳 " + what + " 的資料");
            }
            return body;
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            String reason = switch (status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status) {
                case NOT_FOUND -> "查無" + what;
                case UNAUTHORIZED -> "登入已失效，請重新登入";
                case FORBIDDEN -> "你在訂單系統沒有查詢" + what + " 的權限";
                default -> "訂單系統暫時無法處理（HTTP " + e.getStatusCode().value() + "）";
            };
            throw new LegacyCallException(reason);
        } catch (ResourceAccessException e) {
            throw new LegacyCallException("無法連線到訂單系統，請稍後再試");
        }
    }

    /** 回給 Agent 的精簡結果。 */
    public record OrderStatus(
        String orderId,
        String status,
        List<String> items,
        int totalAmount,
        LocalDateTime createdAt,
        String shipmentId) {}

    /** 舊系統的回應格式（只取需要的欄位，其餘忽略）。 */
    record LegacyOrder(
        String id,
        List<LegacyItem> items,
        int totalAmount,
        String status,
        String shipmentId,
        LocalDateTime createdAt) {}

    record LegacyItem(String name, int quantity) {}
}

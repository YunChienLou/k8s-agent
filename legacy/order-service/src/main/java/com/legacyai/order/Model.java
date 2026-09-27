package com.legacyai.order;

import java.time.LocalDateTime;
import java.util.List;

/** 訂單系統的資料模型。 */
final class Model {
    private Model() {}

    record Customer(String id, String name, String phone, String email, String tier) {}

    record OrderItem(String sku, String name, int quantity, int unitPrice) {}

    enum OrderStatus { PAID, SHIPPED, DELIVERED, CANCELLED }

    record Order(
        String id,
        String customerId,
        List<OrderItem> items,
        int totalAmount,
        OrderStatus status,
        String shippingAddress,
        String shipmentId,
        LocalDateTime createdAt) {}
}

package com.legacyai.logistics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.legacyai.logistics.Model.Shipment;
import com.legacyai.logistics.Model.ShipmentStatus;
import com.legacyai.logistics.Model.TrackingEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 貨件資料（demo 用記憶體保存）。 */
@Component
public class ShipmentStore {

    /** 改寄手續費（業務規則寫死在舊系統裡，這很「傳統」）。 */
    static final int REDIRECT_FEE = 60;

    /** 只有尚未進入最後一哩配送的貨件可以改寄。 */
    static final Set<ShipmentStatus> REDIRECTABLE =
        EnumSet.of(ShipmentStatus.CREATED, ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELAYED);

    private final Map<String, Shipment> shipments = new ConcurrentHashMap<>();

    public ShipmentStore() {
        seed();
    }

    List<Shipment> list(String orderId) {
        return shipments.values().stream()
            .filter(s -> orderId == null || s.orderId().equals(orderId))
            .sorted(Comparator.comparing(Shipment::id))
            .toList();
    }

    Shipment find(String id) {
        Shipment s = shipments.get(id);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無貨件 " + id);
        }
        return s;
    }

    /** 檢查目前是否可改寄；不可改寄時回 409。 */
    void requireRedirectable(Shipment s) {
        if (!REDIRECTABLE.contains(s.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "貨件 " + s.id() + " 狀態為 " + s.status() + "，已無法改寄");
        }
    }

    Shipment applyRedirect(String id, String newAddress, String note) {
        Shipment s = find(id);
        requireRedirectable(s);
        Shipment updated = s.withRedirect(newAddress, s.estimatedDelivery().plusDays(1),
            new TrackingEvent(LocalDateTime.now().withNano(0), "物流系統", note));
        shipments.put(id, updated);
        return updated;
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDate today = LocalDate.now();

        // 主要 demo 情境：轉運中心延誤
        put(new Shipment("S-3001", "O-2001", "黑貓宅急便", "TC-889120034", ShipmentStatus.DELAYED,
            "新北市板橋區文化路一段 100 號 5 樓", today.plusDays(2),
            List.of(
                new TrackingEvent(now.minusDays(7), "台北倉", "已出貨"),
                new TrackingEvent(now.minusDays(6), "桃園轉運中心", "到達轉運中心"),
                new TrackingEvent(now.minusDays(4), "桃園轉運中心", "分揀量過大，配送延誤"))));
        put(new Shipment("S-3002", "O-2002", "黑貓宅急便", "TC-771200981", ShipmentStatus.DELIVERED,
            "新北市板橋區文化路一段 100 號 5 樓", today.minusDays(37),
            List.of(
                new TrackingEvent(now.minusDays(39), "台北倉", "已出貨"),
                new TrackingEvent(now.minusDays(37), "板橋營業所", "已送達，本人簽收"))));
        put(new Shipment("S-3003", "O-2003", "新竹物流", "HC-500213377", ShipmentStatus.OUT_FOR_DELIVERY,
            "台中市西屯區台灣大道三段 99 號", today,
            List.of(
                new TrackingEvent(now.minusDays(2), "台北倉", "已出貨"),
                new TrackingEvent(now.minusHours(3), "台中營業所", "配送中"))));
    }

    private void put(Shipment s) { shipments.put(s.id(), s); }
}

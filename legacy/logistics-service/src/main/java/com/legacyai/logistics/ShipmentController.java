package com.legacyai.logistics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.legacyai.logistics.Model.RedirectRequest;
import com.legacyai.logistics.Model.RedirectResult;
import com.legacyai.logistics.Model.Shipment;
import com.legacyai.logistics.Model.ShipmentStatus;
import com.legacyai.logistics.Model.TrackingEvent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/shipments")
@PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
public class ShipmentController {

    /** 改寄手續費（業務規則寫死在舊系統裡，這很「傳統」）。 */
    static final int REDIRECT_FEE = 60;

    /** 只有尚未進入最後一哩配送的貨件可以改寄。 */
    static final Set<ShipmentStatus> REDIRECTABLE =
        EnumSet.of(ShipmentStatus.CREATED, ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELAYED);

    private final Map<String, Shipment> shipments = new ConcurrentHashMap<>();

    public ShipmentController() {
        seed();
    }

    @GetMapping
    public List<Shipment> list(@RequestParam(required = false) String orderId) {
        return shipments.values().stream()
            .filter(s -> orderId == null || s.orderId().equals(orderId))
            .sorted(Comparator.comparing(Shipment::id))
            .toList();
    }

    @GetMapping("/{id}")
    public Shipment get(@PathVariable String id) {
        return find(id);
    }

    @PostMapping("/{id}/redirect")
    public RedirectResult redirect(@PathVariable String id,
                                   @Valid @RequestBody RedirectRequest req,
                                   Authentication auth) {
        Shipment s = find(id);
        if (!REDIRECTABLE.contains(s.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "貨件狀態為 " + s.status() + "，已無法改寄");
        }
        LocalDate newEta = s.estimatedDelivery().plusDays(1);
        String note = "改寄至新地址（操作人：" + auth.getName()
            + (req.reason() == null || req.reason().isBlank() ? "" : "，原因：" + req.reason()) + "）";
        Shipment updated = s.withRedirect(req.newAddress(), newEta,
            new TrackingEvent(LocalDateTime.now().withNano(0), "客服系統", note));
        shipments.put(id, updated);
        return new RedirectResult(id, s.address(), req.newAddress(), REDIRECT_FEE, newEta);
    }

    private Shipment find(String id) {
        Shipment s = shipments.get(id);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無貨件 " + id);
        }
        return s;
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

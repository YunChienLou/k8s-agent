package com.legacyai.logistics;

import java.util.List;

import com.legacyai.logistics.Model.Shipment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 貨件查詢。改寄一律走申請單流程（{@link RedirectRequestController}），沒有直接改地址的 API。 */
@RestController
@RequestMapping("/api/shipments")
@PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor', 'logistics_staff')")
public class ShipmentController {

    private final ShipmentStore store;

    public ShipmentController(ShipmentStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Shipment> list(@RequestParam(required = false) String orderId) {
        return store.list(orderId);
    }

    @GetMapping("/{id}")
    public Shipment get(@PathVariable String id) {
        return store.find(id);
    }
}

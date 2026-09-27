package com.legacyai.logistics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 物流系統的資料模型。 */
final class Model {
    private Model() {}

    enum ShipmentStatus { CREATED, IN_TRANSIT, DELAYED, OUT_FOR_DELIVERY, DELIVERED }

    record TrackingEvent(LocalDateTime time, String location, String description) {}

    record Shipment(
        String id,
        String orderId,
        String carrier,
        String trackingNo,
        ShipmentStatus status,
        String address,
        LocalDate estimatedDelivery,
        List<TrackingEvent> events) {

        Shipment withRedirect(String newAddress, LocalDate newEta, TrackingEvent event) {
            List<TrackingEvent> updated = new java.util.ArrayList<>(events);
            updated.add(event);
            return new Shipment(id, orderId, carrier, trackingNo, status, newAddress, newEta, List.copyOf(updated));
        }
    }

    record RedirectRequest(
        @NotBlank @Size(max = 200) String newAddress,
        @Size(max = 200) String reason) {}

    record RedirectResult(String shipmentId, String oldAddress, String newAddress, int fee, LocalDate estimatedDelivery) {}
}

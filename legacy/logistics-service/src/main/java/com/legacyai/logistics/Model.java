package com.legacyai.logistics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 物流系統的資料模型。 */
final class Model {
    private Model() {}

    enum ShipmentStatus { CREATED, IN_TRANSIT, DELAYED, OUT_FOR_DELIVERY, DELIVERED }

    /** 申請單的來源管道：舊系統原本就有的欄位，AI 只是多一個選項。 */
    enum Channel { WEB, PHONE, AI_COPILOT }

    enum RequestStatus { PENDING, EXECUTED, REJECTED }

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
            List<TrackingEvent> updated = new ArrayList<>(events);
            updated.add(event);
            return new Shipment(id, orderId, carrier, trackingNo, status, newAddress, newEta, List.copyOf(updated));
        }
    }

    /** 改寄申請單：建立後為 PENDING，由物流人員執行或退回。 */
    record RedirectRequest(
        String id,
        String shipmentId,
        String oldAddress,
        String newAddress,
        String reason,
        int fee,
        Channel channel,
        String externalRef,
        String requestedBy,
        String submittedVia,
        String actor,
        RequestStatus status,
        LocalDateTime createdAt,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionNote) {

        RedirectRequest decide(RequestStatus newStatus, String by, String note) {
            return new RedirectRequest(id, shipmentId, oldAddress, newAddress, reason, fee, channel, externalRef,
                requestedBy, submittedVia, actor, newStatus, createdAt, by, LocalDateTime.now().withNano(0), note);
        }
    }

    record CreateRedirectRequest(
        @NotBlank String shipmentId,
        @NotBlank @Size(max = 200) String newAddress,
        @Size(max = 200) String reason,
        Channel channel,
        @Size(max = 64) String externalRef) {}

    record RejectBody(@NotBlank @Size(max = 200) String reason) {}
}

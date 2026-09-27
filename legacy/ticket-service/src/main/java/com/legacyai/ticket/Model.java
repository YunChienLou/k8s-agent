package com.legacyai.ticket;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 客服工單系統的資料模型。 */
final class Model {
    private Model() {}

    enum TicketStatus { OPEN, PENDING_CUSTOMER, RESOLVED }

    enum Priority { LOW, NORMAL, HIGH }

    enum CompensationType { SHIPPING_FEE_REFUND, COUPON }

    record Note(String author, String content, LocalDateTime createdAt) {}

    record Ticket(
        String id,
        String customerId,
        String orderId,
        String subject,
        String description,
        TicketStatus status,
        Priority priority,
        String assignee,
        LocalDateTime createdAt,
        List<Note> notes) {

        Ticket withNote(Note note) {
            List<Note> updated = new ArrayList<>(notes);
            updated.add(note);
            return new Ticket(id, customerId, orderId, subject, description, status, priority,
                assignee, createdAt, List.copyOf(updated));
        }

        Ticket withStatus(TicketStatus newStatus) {
            return new Ticket(id, customerId, orderId, subject, description, newStatus, priority,
                assignee, createdAt, notes);
        }
    }

    record Compensation(
        String id,
        String ticketId,
        String customerId,
        CompensationType type,
        int amount,
        String reason,
        String createdBy,
        LocalDateTime createdAt) {}

    record NoteRequest(@NotBlank @Size(max = 1000) String content) {}

    record StatusRequest(@NotNull TicketStatus status) {}

    record CompensationRequest(
        @NotNull CompensationType type,
        @Positive @Max(10_000) int amount,
        @NotBlank @Size(max = 200) String reason) {}
}

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

    /** 來源管道：舊系統原本就有的欄位，AI 只是多一個選項。 */
    enum Channel { WEB, PHONE, AI_COPILOT }

    enum RequestStatus { PENDING_APPROVAL, APPROVED, REJECTED }

    /** 建立補償的結果：在權限上限內直接生效，超過則進入主管簽核。 */
    enum Outcome { APPLIED, PENDING_APPROVAL }

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

    /** 已生效的補償。requestId 不為 null 表示經主管簽核。 */
    record Compensation(
        String id,
        String ticketId,
        String customerId,
        CompensationType type,
        int amount,
        String reason,
        Channel channel,
        String externalRef,
        String createdBy,
        String submittedVia,
        String approvedBy,
        String requestId,
        LocalDateTime createdAt) {}

    /** 超過權限上限的補償申請，待主管簽核。 */
    record CompensationRequest(
        String id,
        String ticketId,
        String customerId,
        CompensationType type,
        int amount,
        String reason,
        Channel channel,
        String externalRef,
        String requestedBy,
        String submittedVia,
        String actor,
        RequestStatus status,
        LocalDateTime createdAt,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionNote,
        String compensationId) {

        CompensationRequest decide(RequestStatus newStatus, String by, String note, String compId) {
            return new CompensationRequest(id, ticketId, customerId, type, amount, reason, channel, externalRef,
                requestedBy, submittedVia, actor, newStatus, createdAt, by, LocalDateTime.now().withNano(0),
                note, compId);
        }
    }

    record CompensationResult(Outcome outcome, Compensation compensation, CompensationRequest request) {}

    record NoteRequest(@NotBlank @Size(max = 1000) String content) {}

    record StatusRequest(@NotNull TicketStatus status) {}

    record CompensationBody(
        @NotNull CompensationType type,
        @Positive @Max(10_000) int amount,
        @NotBlank @Size(max = 200) String reason,
        Channel channel,
        @Size(max = 64) String externalRef) {}

    record RejectBody(@NotBlank @Size(max = 200) String reason) {}
}

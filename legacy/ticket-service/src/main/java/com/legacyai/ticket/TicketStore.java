package com.legacyai.ticket;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.legacyai.ticket.Model.Compensation;
import com.legacyai.ticket.Model.CompensationBody;
import com.legacyai.ticket.Model.CompensationRequest;
import com.legacyai.ticket.Model.CompensationResult;
import com.legacyai.ticket.Model.Channel;
import com.legacyai.ticket.Model.Outcome;
import com.legacyai.ticket.Model.Priority;
import com.legacyai.ticket.Model.RequestStatus;
import com.legacyai.ticket.Model.Ticket;
import com.legacyai.ticket.Model.TicketStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工單、補償與補償申請（demo 用記憶體保存）。
 * 權限規則（Agent 必須繼承，不能繞過）：
 * <ul>
 *   <li>cs_agent 只能看、改指派給自己的工單；cs_supervisor 可以看全部</li>
 *   <li>單筆補償上限：cs_agent 100 元、cs_supervisor 500 元；超過自己上限則建立待主管簽核的申請</li>
 * </ul>
 */
@Component
public class TicketStore {

    static final int AGENT_LIMIT = 100;
    static final int SUPERVISOR_LIMIT = 500;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final List<Compensation> compensations = new CopyOnWriteArrayList<>();
    private final Map<String, CompensationRequest> requests = new ConcurrentHashMap<>();
    private final AtomicInteger compSeq = new AtomicInteger(5000);
    private final AtomicInteger reqSeq = new AtomicInteger(5100);

    public TicketStore() {
        seed();
    }

    /* ---------- 工單 ---------- */

    List<Ticket> listTickets(TicketStatus status, Authentication auth) {
        return tickets.values().stream()
            .filter(t -> isSupervisor(auth) || t.assignee().equals(auth.getName()))
            .filter(t -> status == null || t.status() == status)
            .sorted(Comparator.comparing(Ticket::createdAt).reversed())
            .toList();
    }

    Ticket findAccessible(String id, Authentication auth) {
        Ticket t = tickets.get(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無工單 " + id);
        }
        if (!isSupervisor(auth) && !t.assignee().equals(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "工單 " + id + " 未指派給你，無權存取");
        }
        return t;
    }

    void saveTicket(Ticket t) { tickets.put(t.id(), t); }

    /* ---------- 補償 ---------- */

    List<Compensation> compensationsOf(String ticketId) {
        return compensations.stream().filter(c -> c.ticketId().equals(ticketId)).toList();
    }

    /** 上限內直接生效；超過則建立待主管簽核的申請。externalRef 相同時回傳既有結果（冪等）。 */
    synchronized Optional<CompensationResult> findByExternalRef(String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            return Optional.empty();
        }
        Optional<Compensation> direct = compensations.stream()
            .filter(c -> externalRef.equals(c.externalRef()) && c.requestId() == null).findFirst();
        if (direct.isPresent()) {
            return Optional.of(new CompensationResult(Outcome.APPLIED, direct.get(), null));
        }
        return requests.values().stream().filter(r -> externalRef.equals(r.externalRef())).findFirst()
            .map(r -> new CompensationResult(Outcome.PENDING_APPROVAL, null, r));
    }

    synchronized CompensationResult createCompensation(Ticket t, CompensationBody body, Authentication auth) {
        Channel channel = body.channel() == null ? Channel.WEB : body.channel();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (body.amount() <= limitOf(auth)) {
            Compensation c = new Compensation("CP-" + compSeq.incrementAndGet(), t.id(), t.customerId(),
                body.type(), body.amount(), body.reason(), channel, body.externalRef(), auth.getName(),
                AuthInfo.clientId(auth), null, null, now);
            compensations.add(c);
            return new CompensationResult(Outcome.APPLIED, c, null);
        }
        CompensationRequest r = new CompensationRequest("CR-" + reqSeq.incrementAndGet(), t.id(), t.customerId(),
            body.type(), body.amount(), body.reason(), channel, body.externalRef(), auth.getName(),
            AuthInfo.clientId(auth), AuthInfo.actor(auth), RequestStatus.PENDING_APPROVAL, now,
            null, null, null, null);
        requests.put(r.id(), r);
        return new CompensationResult(Outcome.PENDING_APPROVAL, null, r);
    }

    /* ---------- 補償申請（主管簽核） ---------- */

    List<CompensationRequest> listRequests(RequestStatus status, Authentication auth) {
        return requests.values().stream()
            .filter(r -> isSupervisor(auth) || r.requestedBy().equals(auth.getName()))
            .filter(r -> status == null || r.status() == status)
            .sorted(Comparator.comparing(CompensationRequest::createdAt).reversed())
            .toList();
    }

    CompensationRequest findRequest(String id, Authentication auth) {
        CompensationRequest r = requests.get(id);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無補償申請 " + id);
        }
        if (!isSupervisor(auth) && !r.requestedBy().equals(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "無權查看補償申請 " + id);
        }
        return r;
    }

    synchronized CompensationRequest approve(String id, Authentication auth) {
        CompensationRequest r = requirePending(id, auth);
        if (r.amount() > SUPERVISOR_LIMIT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "補償金額 " + r.amount() + " 元超過主管單筆上限 " + SUPERVISOR_LIMIT + " 元，需另行專案處理");
        }
        Compensation c = new Compensation("CP-" + compSeq.incrementAndGet(), r.ticketId(), r.customerId(),
            r.type(), r.amount(), r.reason(), r.channel(), r.externalRef(), r.requestedBy(), r.submittedVia(),
            auth.getName(), r.id(), LocalDateTime.now().withNano(0));
        compensations.add(c);
        CompensationRequest done = r.decide(RequestStatus.APPROVED, auth.getName(), null, c.id());
        requests.put(id, done);
        return done;
    }

    synchronized CompensationRequest reject(String id, String reason, Authentication auth) {
        CompensationRequest r = requirePending(id, auth);
        CompensationRequest done = r.decide(RequestStatus.REJECTED, auth.getName(), reason, null);
        requests.put(id, done);
        return done;
    }

    private CompensationRequest requirePending(String id, Authentication auth) {
        CompensationRequest r = findRequest(id, auth);
        if (r.status() != RequestStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "補償申請 " + id + " 狀態為 " + r.status() + "，無法再處理");
        }
        return r;
    }

    static boolean isSupervisor(Authentication auth) {
        return AuthInfo.hasRole(auth, "cs_supervisor");
    }

    static int limitOf(Authentication auth) {
        return isSupervisor(auth) ? SUPERVISOR_LIMIT : AGENT_LIMIT;
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        saveTicket(new Ticket("T-1001", "C-001", "O-2001",
            "外套還沒收到，想改寄公司",
            "客人表示上週買的藍色羽絨外套還沒收到，下週要出差，希望改寄到公司"
                + "（台北市信義區松仁路 100 號 12 樓）。詢問能否補償運費。",
            TicketStatus.OPEN, Priority.HIGH, "alice", now.minusHours(2), List.of()));
        saveTicket(new Ticket("T-1002", "C-002", "O-2003",
            "發票抬頭開錯",
            "客人需要公司抬頭與統編，原訂單開成個人發票。",
            TicketStatus.OPEN, Priority.NORMAL, "bob", now.minusHours(5), List.of()));
        saveTicket(new Ticket("T-1003", "C-003", "O-2004",
            "想取消訂單",
            "客人下單後發現尺寸買錯，想取消訂單重新下單。",
            TicketStatus.OPEN, Priority.NORMAL, "alice", now.minusMinutes(30), List.of()));
    }
}

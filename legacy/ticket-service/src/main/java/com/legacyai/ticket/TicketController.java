package com.legacyai.ticket;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.legacyai.ticket.Model.Compensation;
import com.legacyai.ticket.Model.CompensationRequest;
import com.legacyai.ticket.Model.Note;
import com.legacyai.ticket.Model.NoteRequest;
import com.legacyai.ticket.Model.Priority;
import com.legacyai.ticket.Model.StatusRequest;
import com.legacyai.ticket.Model.Ticket;
import com.legacyai.ticket.Model.TicketStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 權限規則（這些是 demo 的重點，Agent 之後必須「繼承」它們，而不是繞過）：
 * <ul>
 *   <li>cs_agent 只能看、改指派給自己的工單；cs_supervisor 可以看全部</li>
 *   <li>單筆補償上限：cs_agent 100 元、cs_supervisor 500 元</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tickets")
@PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
public class TicketController {

    static final int AGENT_COMPENSATION_LIMIT = 100;
    static final int SUPERVISOR_COMPENSATION_LIMIT = 500;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final List<Compensation> compensations = new CopyOnWriteArrayList<>();
    private final AtomicInteger compensationSeq = new AtomicInteger(5000);

    public TicketController() {
        seed();
    }

    @GetMapping
    public List<Ticket> list(@RequestParam(required = false) TicketStatus status, Authentication auth) {
        return tickets.values().stream()
            .filter(t -> isSupervisor(auth) || t.assignee().equals(auth.getName()))
            .filter(t -> status == null || t.status() == status)
            .sorted(Comparator.comparing(Ticket::createdAt).reversed())
            .toList();
    }

    @GetMapping("/{id}")
    public Ticket get(@PathVariable String id, Authentication auth) {
        return findAccessible(id, auth);
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Ticket addNote(@PathVariable String id, @Valid @RequestBody NoteRequest req, Authentication auth) {
        Ticket t = findAccessible(id, auth);
        Ticket updated = t.withNote(new Note(auth.getName(), req.content(), LocalDateTime.now().withNano(0)));
        tickets.put(id, updated);
        return updated;
    }

    @PatchMapping("/{id}/status")
    public Ticket updateStatus(@PathVariable String id, @Valid @RequestBody StatusRequest req, Authentication auth) {
        Ticket updated = findAccessible(id, auth).withStatus(req.status());
        tickets.put(id, updated);
        return updated;
    }

    @GetMapping("/{id}/compensations")
    public List<Compensation> listCompensations(@PathVariable String id, Authentication auth) {
        findAccessible(id, auth);
        return compensations.stream().filter(c -> c.ticketId().equals(id)).toList();
    }

    @PostMapping("/{id}/compensations")
    @ResponseStatus(HttpStatus.CREATED)
    public Compensation createCompensation(@PathVariable String id,
                                           @Valid @RequestBody CompensationRequest req,
                                           Authentication auth) {
        Ticket t = findAccessible(id, auth);
        int limit = isSupervisor(auth) ? SUPERVISOR_COMPENSATION_LIMIT : AGENT_COMPENSATION_LIMIT;
        if (req.amount() > limit) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "補償金額 " + req.amount() + " 元超過你的權限上限 " + limit + " 元，請交由主管處理");
        }
        Compensation c = new Compensation("CP-" + compensationSeq.incrementAndGet(), id, t.customerId(),
            req.type(), req.amount(), req.reason(), auth.getName(), LocalDateTime.now().withNano(0));
        compensations.add(c);
        return c;
    }

    private Ticket findAccessible(String id, Authentication auth) {
        Ticket t = tickets.get(id);
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無工單 " + id);
        }
        if (!isSupervisor(auth) && !t.assignee().equals(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "工單 " + id + " 未指派給你，無權存取");
        }
        return t;
    }

    private static boolean isSupervisor(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_cs_supervisor".equals(a.getAuthority()));
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        put(new Ticket("T-1001", "C-001", "O-2001",
            "外套還沒收到，想改寄公司",
            "客人表示上週買的藍色羽絨外套還沒收到，下週要出差，希望改寄到公司"
                + "（台北市信義區松仁路 100 號 12 樓）。詢問能否補償運費。",
            TicketStatus.OPEN, Priority.HIGH, "alice", now.minusHours(2), List.of()));
        put(new Ticket("T-1002", "C-002", "O-2003",
            "發票抬頭開錯",
            "客人需要公司抬頭與統編，原訂單開成個人發票。",
            TicketStatus.OPEN, Priority.NORMAL, "bob", now.minusHours(5), List.of()));
        put(new Ticket("T-1003", "C-003", "O-2004",
            "想取消訂單",
            "客人下單後發現尺寸買錯，想取消訂單重新下單。",
            TicketStatus.OPEN, Priority.NORMAL, "alice", now.minusMinutes(30), List.of()));
    }

    private void put(Ticket t) { tickets.put(t.id(), t); }
}

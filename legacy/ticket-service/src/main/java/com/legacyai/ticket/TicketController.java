package com.legacyai.ticket;

import java.time.LocalDateTime;
import java.util.List;

import com.legacyai.ticket.Model.Compensation;
import com.legacyai.ticket.Model.CompensationBody;
import com.legacyai.ticket.Model.CompensationResult;
import com.legacyai.ticket.Model.Note;
import com.legacyai.ticket.Model.NoteRequest;
import com.legacyai.ticket.Model.Outcome;
import com.legacyai.ticket.Model.StatusRequest;
import com.legacyai.ticket.Model.Ticket;
import com.legacyai.ticket.Model.TicketStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/tickets")
@PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
public class TicketController {

    private final TicketStore store;

    public TicketController(TicketStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Ticket> list(@RequestParam(required = false) TicketStatus status, Authentication auth) {
        return store.listTickets(status, auth);
    }

    @GetMapping("/{id}")
    public Ticket get(@PathVariable String id, Authentication auth) {
        return store.findAccessible(id, auth);
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Ticket addNote(@PathVariable String id, @Valid @RequestBody NoteRequest req, Authentication auth) {
        Ticket updated = store.findAccessible(id, auth)
            .withNote(new Note(auth.getName(), req.content(), LocalDateTime.now().withNano(0)));
        store.saveTicket(updated);
        return updated;
    }

    @PatchMapping("/{id}/status")
    public Ticket updateStatus(@PathVariable String id, @Valid @RequestBody StatusRequest req, Authentication auth) {
        Ticket updated = store.findAccessible(id, auth).withStatus(req.status());
        store.saveTicket(updated);
        return updated;
    }

    @GetMapping("/{id}/compensations")
    public List<Compensation> listCompensations(@PathVariable String id, Authentication auth) {
        store.findAccessible(id, auth);
        return store.compensationsOf(id);
    }

    /**
     * 建立補償：金額在自己上限內 → 201 直接生效；超過 → 202 建立待主管簽核的申請。
     * externalRef 重送 → 200 回傳既有結果。
     */
    @PostMapping("/{id}/compensations")
    public ResponseEntity<CompensationResult> createCompensation(@PathVariable String id,
                                                                 @Valid @RequestBody CompensationBody body,
                                                                 Authentication auth) {
        Ticket t = store.findAccessible(id, auth);
        var existing = store.findByExternalRef(body.externalRef());
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }
        CompensationResult result = store.createCompensation(t, body, auth);
        HttpStatus status = result.outcome() == Outcome.APPLIED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result);
    }
}

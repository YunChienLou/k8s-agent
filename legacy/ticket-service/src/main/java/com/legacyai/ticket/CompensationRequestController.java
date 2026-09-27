package com.legacyai.ticket;

import java.util.List;

import com.legacyai.ticket.Model.CompensationRequest;
import com.legacyai.ticket.Model.RejectBody;
import com.legacyai.ticket.Model.RequestStatus;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 補償申請的主管簽核：舊系統原本的簽核流程。 */
@RestController
@RequestMapping("/api/compensation-requests")
public class CompensationRequestController {

    private final TicketStore store;

    public CompensationRequestController(TicketStore store) {
        this.store = store;
    }

    /** 主管看全部；客服只看自己提出的。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
    public List<CompensationRequest> list(@RequestParam(required = false) RequestStatus status, Authentication auth) {
        return store.listRequests(status, auth);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
    public CompensationRequest get(@PathVariable String id, Authentication auth) {
        return store.findRequest(id, auth);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('cs_supervisor')")
    public CompensationRequest approve(@PathVariable String id, Authentication auth) {
        return store.approve(id, auth);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('cs_supervisor')")
    public CompensationRequest reject(@PathVariable String id, @Valid @RequestBody RejectBody body, Authentication auth) {
        return store.reject(id, body.reason(), auth);
    }
}

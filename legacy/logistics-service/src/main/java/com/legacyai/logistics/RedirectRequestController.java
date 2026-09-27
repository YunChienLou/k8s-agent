package com.legacyai.logistics;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.legacyai.logistics.Model.Channel;
import com.legacyai.logistics.Model.CreateRedirectRequest;
import com.legacyai.logistics.Model.RedirectRequest;
import com.legacyai.logistics.Model.RejectBody;
import com.legacyai.logistics.Model.RequestStatus;
import com.legacyai.logistics.Model.Shipment;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * 改寄申請單：舊系統原本的開單／審核流程。
 * <ul>
 *   <li>客服（含經由 Copilot）只能「建立申請」，不能直接改地址</li>
 *   <li>物流人員在物流系統畫面「執行」或「退回」</li>
 *   <li>externalRef 相同的申請重送時回傳既有申請單（冪等，ADR-0006）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/redirect-requests")
public class RedirectRequestController {

    private final ShipmentStore shipments;
    private final Map<String, RedirectRequest> requests = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(7000);

    public RedirectRequestController(ShipmentStore shipments) {
        this.shipments = shipments;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor')")
    public synchronized ResponseEntity<RedirectRequest> create(@Valid @RequestBody CreateRedirectRequest req, Authentication auth) {
        if (req.externalRef() != null && !req.externalRef().isBlank()) {
            Optional<RedirectRequest> existing = requests.values().stream()
                .filter(r -> req.externalRef().equals(r.externalRef()))
                .findFirst();
            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }
        }
        Shipment s = shipments.find(req.shipmentId());
        shipments.requireRedirectable(s);
        boolean hasPending = requests.values().stream()
            .anyMatch(r -> r.shipmentId().equals(s.id()) && r.status() == RequestStatus.PENDING);
        if (hasPending) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "貨件 " + s.id() + " 已有待處理的改寄申請");
        }
        RedirectRequest r = new RedirectRequest(
            "RR-" + seq.incrementAndGet(), s.id(), s.address(), req.newAddress(), req.reason(),
            ShipmentStore.REDIRECT_FEE, req.channel() == null ? Channel.WEB : req.channel(),
            req.externalRef(), auth.getName(), AuthInfo.clientId(auth), AuthInfo.actor(auth),
            RequestStatus.PENDING, LocalDateTime.now().withNano(0), null, null, null);
        requests.put(r.id(), r);
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor', 'logistics_staff')")
    public List<RedirectRequest> list(@RequestParam(required = false) RequestStatus status, Authentication auth) {
        return requests.values().stream()
            .filter(r -> canSee(r, auth))
            .filter(r -> status == null || r.status() == status)
            .sorted(Comparator.comparing(RedirectRequest::createdAt).reversed())
            .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('cs_agent', 'cs_supervisor', 'logistics_staff')")
    public RedirectRequest get(@PathVariable String id, Authentication auth) {
        RedirectRequest r = find(id);
        if (!canSee(r, auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "無權查看申請單 " + id);
        }
        return r;
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasRole('logistics_staff')")
    public synchronized RedirectRequest execute(@PathVariable String id, Authentication auth) {
        RedirectRequest r = requirePending(id);
        shipments.applyRedirect(r.shipmentId(), r.newAddress(),
            "依改寄申請 " + r.id() + " 改寄（申請人：" + r.requestedBy() + "，執行人：" + auth.getName() + "）");
        RedirectRequest done = r.decide(RequestStatus.EXECUTED, auth.getName(), null);
        requests.put(id, done);
        return done;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('logistics_staff')")
    public synchronized RedirectRequest reject(@PathVariable String id, @Valid @RequestBody RejectBody body,
                                               Authentication auth) {
        RedirectRequest r = requirePending(id);
        RedirectRequest done = r.decide(RequestStatus.REJECTED, auth.getName(), body.reason());
        requests.put(id, done);
        return done;
    }

    private RedirectRequest find(String id) {
        RedirectRequest r = requests.get(id);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查無申請單 " + id);
        }
        return r;
    }

    private RedirectRequest requirePending(String id) {
        RedirectRequest r = find(id);
        if (r.status() != RequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "申請單 " + id + " 狀態為 " + r.status() + "，無法再處理");
        }
        return r;
    }

    /** 物流人員與客服主管可看全部；客服只能看自己提出的申請。 */
    private static boolean canSee(RedirectRequest r, Authentication auth) {
        return AuthInfo.hasRole(auth, "logistics_staff")
            || AuthInfo.hasRole(auth, "cs_supervisor")
            || r.requestedBy().equals(auth.getName());
    }
}

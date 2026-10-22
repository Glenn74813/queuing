package ph.gov.philhealth.qms.queue;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.counter.AgentSessionRepository;
import ph.gov.philhealth.qms.queue.QueueDtos.AgentSessionDto;
import ph.gov.philhealth.qms.queue.QueueDtos.OptionalReasonRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.QueueSnapshot;
import ph.gov.philhealth.qms.queue.QueueDtos.ReasonRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.StartSessionRequest;
import ph.gov.philhealth.qms.queue.QueueDtos.TicketDetail;
import ph.gov.philhealth.qms.queue.QueueDtos.TicketDto;
import ph.gov.philhealth.qms.queue.QueueDtos.TransferRequest;
import ph.gov.philhealth.qms.security.CurrentUser;

@RestController
public class AgentController {

    private final AgentSessionService sessions;
    private final AgentSessionRepository sessionRepo;
    private final QueueService queue;
    private final QueueQueryService queries;
    private final TicketRepository tickets;
    private final TicketMapper mapper;

    public AgentController(AgentSessionService sessions, AgentSessionRepository sessionRepo, QueueService queue,
                           QueueQueryService queries, TicketRepository tickets, TicketMapper mapper) {
        this.sessions = sessions;
        this.sessionRepo = sessionRepo;
        this.queue = queue;
        this.queries = queries;
        this.tickets = tickets;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ sessions

    @GetMapping("/api/v1/agent/session")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public ResponseEntity<AgentSessionDto> session() {
        return sessions.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/v1/agent/session/start")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public AgentSessionDto start(@Valid @RequestBody StartSessionRequest req) {
        return sessions.start(req.counterId());
    }

    @PostMapping("/api/v1/agent/session/end")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public ResponseEntity<Void> end() {
        sessions.end();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/agent/session/break/start")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public AgentSessionDto startBreak(@Valid @RequestBody(required = false) OptionalReasonRequest req) {
        return sessions.startBreak(req != null ? req.reason() : null);
    }

    @PostMapping("/api/v1/agent/session/break/end")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public AgentSessionDto endBreak() {
        return sessions.endBreak();
    }

    @PostMapping("/api/v1/sites/{siteId}/counters/{counterId}/force-end-session")
    @PreAuthorize("hasAuthority('QUEUE_SUPERVISE')")
    public ResponseEntity<Void> forceEnd(@PathVariable UUID siteId, @PathVariable UUID counterId,
                                         @Valid @RequestBody ReasonRequest req) {
        sessions.forceEnd(siteId, counterId, req.reason());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ agent queue

    @GetMapping("/api/v1/agent/current-ticket")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    @Transactional(readOnly = true)
    public ResponseEntity<TicketDto> currentTicket() {
        return sessionRepo.findOpenByUser(CurrentUser.get().id())
                .flatMap(s -> tickets.findCalledAtCounter(s.getCounterId()).stream().findFirst())
                .map(t -> ResponseEntity.ok(mapper.toDto(t)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/v1/agent/held-tickets")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    @Transactional(readOnly = true)
    public List<TicketDto> heldTickets() {
        return sessionRepo.findOpenByUser(CurrentUser.get().id())
                .map(s -> tickets.findHeldAtCounter(s.getCounterId()).stream().map(mapper::toDto).toList())
                .orElse(List.of());
    }

    @PostMapping("/api/v1/agent/call-next")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public ResponseEntity<TicketDto> callNext() {
        return queue.callNext().map(t -> ResponseEntity.ok(mapper.toDto(t)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // ------------------------------------------------------------------ ticket actions

    @GetMapping("/api/v1/tickets/{id}")
    public TicketDetail ticket(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PostMapping("/api/v1/tickets/{id}/recall")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto recall(@PathVariable UUID id) {
        return mapper.toDto(queue.recall(id));
    }

    @PostMapping("/api/v1/tickets/{id}/complete")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto complete(@PathVariable UUID id) {
        return mapper.toDto(queue.complete(id));
    }

    @PostMapping("/api/v1/tickets/{id}/hold")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto hold(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return mapper.toDto(queue.hold(id, req.reason()));
    }

    @PostMapping("/api/v1/tickets/{id}/resume")
    @PreAuthorize("hasAuthority('QUEUE_SERVE')")
    public TicketDto resume(@PathVariable UUID id) {
        return mapper.toDto(queue.resume(id));
    }

    @PostMapping("/api/v1/tickets/{id}/transfer")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest req) {
        return mapper.toDto(queue.transfer(id, req.targetSiteServiceId(), req.reason()));
    }

    @PostMapping("/api/v1/tickets/{id}/next-stage")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto nextStage(@PathVariable UUID id) {
        return mapper.toDto(queue.nextStage(id));
    }

    @PostMapping("/api/v1/tickets/{id}/remove")
    @PreAuthorize("hasAnyAuthority('QUEUE_SERVE','QUEUE_SUPERVISE')")
    public TicketDto remove(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return mapper.toDto(queue.remove(id, req.reason()));
    }

    @PostMapping("/api/v1/tickets/{id}/void")
    @PreAuthorize("hasAuthority('TICKET_VOID')")
    public TicketDto voidTicket(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return mapper.toDto(queue.voidTicket(id, req.reason()));
    }

    /** Dashboard across all sites in the caller's scope (every site for national roles). */
    @GetMapping("/api/v1/dashboard/overview")
    @PreAuthorize("hasAuthority('QUEUE_VIEW')")
    public QueueDtos.Overview overview(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int days) {
        return queries.overview(days);
    }

    @GetMapping("/api/v1/sites/{siteId}/service-volume")
    @PreAuthorize("hasAuthority('QUEUE_VIEW')")
    public QueueDtos.ServiceVolumeReport serviceVolume(@PathVariable UUID siteId,
                                                       @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int days) {
        return queries.serviceVolume(siteId, days);
    }

    @GetMapping("/api/v1/sites/{siteId}/queue")
    @PreAuthorize("hasAuthority('QUEUE_VIEW')")
    public QueueSnapshot snapshot(@PathVariable UUID siteId) {
        return queries.snapshot(siteId);
    }
}

package ph.gov.philhealth.qms.queue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.catalog.SiteServiceRepository;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.counter.AgentBreak;
import ph.gov.philhealth.qms.counter.AgentBreakRepository;
import ph.gov.philhealth.qms.counter.AgentSession;
import ph.gov.philhealth.qms.counter.AgentSessionRepository;
import ph.gov.philhealth.qms.counter.ServiceCounter;
import ph.gov.philhealth.qms.counter.ServiceCounterRepository;
import ph.gov.philhealth.qms.queue.QueueDtos.AgentSessionDto;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/** Agent sign-in at counters, breaks, and supervisor intervention. */
@Service
public class AgentSessionService {

    private final AgentSessionRepository sessions;
    private final AgentBreakRepository breaks;
    private final ServiceCounterRepository counters;
    private final SiteServiceRepository siteServices;
    private final TicketRepository tickets;
    private final QueueService queue;
    private final ScopeService scope;
    private final AuditService audit;
    private final TimeService time;
    private final QueueBroadcaster broadcaster;

    public AgentSessionService(AgentSessionRepository sessions, AgentBreakRepository breaks,
                               ServiceCounterRepository counters, SiteServiceRepository siteServices,
                               TicketRepository tickets, QueueService queue, ScopeService scope, AuditService audit,
                               TimeService time, QueueBroadcaster broadcaster) {
        this.sessions = sessions;
        this.breaks = breaks;
        this.counters = counters;
        this.siteServices = siteServices;
        this.tickets = tickets;
        this.queue = queue;
        this.scope = scope;
        this.audit = audit;
        this.time = time;
        this.broadcaster = broadcaster;
    }

    @Transactional(readOnly = true)
    public Optional<AgentSessionDto> current() {
        return sessions.findOpenByUser(CurrentUser.get().id()).map(this::toDto);
    }

    @Transactional
    public AgentSessionDto start(UUID counterId) {
        AuthUser user = CurrentUser.get();
        ServiceCounter counter = counters.findById(counterId).orElseThrow(() -> ApiException.notFound("Counter"));
        scope.requireSite(Permission.QUEUE_SERVE, counter.getSiteId());
        if (!counter.isActive()) {
            throw ApiException.conflict("Counter " + counter.getName() + " is inactive");
        }
        Optional<AgentSession> open = sessions.findOpenByUser(user.id());
        if (open.isPresent()) {
            if (open.get().getCounterId().equals(counterId)) {
                return toDto(open.get());
            }
            throw ApiException.conflict("You already have an open session at another counter");
        }
        AgentSession s = new AgentSession();
        s.setId(Ids.newId());
        s.setUserId(user.id());
        s.setCounterId(counterId);
        s.setSiteId(counter.getSiteId());
        s.setStatus(AgentSession.Status.AVAILABLE);
        s.setStartedAt(time.now());
        // The partial unique index rejects a second open session on the same counter.
        sessions.saveAndFlush(s);
        audit.record("AGENT_SESSION_STARTED", "ServiceCounter", counterId, counter.getSiteId(), counter.getName());
        broadcastChange(s.getSiteId());
        return toDto(s);
    }

    @Transactional
    public void end() {
        AuthUser user = CurrentUser.get();
        AgentSession s = sessions.findOpenByUser(user.id()).orElseThrow(() -> ApiException.conflict("No open session"));
        if (!tickets.findCalledAtCounter(s.getCounterId()).isEmpty()
                || !tickets.findHeldAtCounter(s.getCounterId()).isEmpty()) {
            throw ApiException.conflict("Complete, transfer or remove your current and held tickets first");
        }
        close(s);
        audit.record("AGENT_SESSION_ENDED", "ServiceCounter", s.getCounterId(), s.getSiteId(), null);
    }

    @Transactional
    public AgentSessionDto startBreak(String reason) {
        AuthUser user = CurrentUser.get();
        AgentSession s = sessions.findOpenByUser(user.id()).orElseThrow(() -> ApiException.conflict("No open session"));
        if (s.getStatus() != AgentSession.Status.AVAILABLE) {
            throw ApiException.conflict("Already on break");
        }
        if (!tickets.findCalledAtCounter(s.getCounterId()).isEmpty()) {
            throw ApiException.conflict("Finish or hold the current ticket before going on break");
        }
        Instant now = time.now();
        s.setStatus(AgentSession.Status.ON_BREAK);
        s.setBreakStartedAt(now);
        s.setBreakReason(reason);
        AgentBreak b = new AgentBreak();
        b.setId(Ids.newId());
        b.setSessionId(s.getId());
        b.setStartedAt(now);
        b.setReason(reason);
        breaks.save(b);
        broadcastChange(s.getSiteId());
        return toDto(s);
    }

    @Transactional
    public AgentSessionDto endBreak() {
        AuthUser user = CurrentUser.get();
        AgentSession s = sessions.findOpenByUser(user.id()).orElseThrow(() -> ApiException.conflict("No open session"));
        if (s.getStatus() != AgentSession.Status.ON_BREAK) {
            throw ApiException.conflict("Not on break");
        }
        closeBreak(s, time.now());
        s.setStatus(AgentSession.Status.AVAILABLE);
        broadcastChange(s.getSiteId());
        return toDto(s);
    }

    /**
     * Supervisor intervention: signs the agent out of a counter. Tickets still at the counter are
     * returned to the front of their service queue so no client is lost.
     */
    @Transactional
    public void forceEnd(UUID siteId, UUID counterId, String reason) {
        AuthUser user = CurrentUser.get();
        scope.requireSite(Permission.QUEUE_SUPERVISE, siteId);
        AgentSession s = sessions.findOpenBySite(siteId).stream().filter(x -> x.getCounterId().equals(counterId))
                .findFirst().orElseThrow(() -> ApiException.notFound("Open session at this counter"));
        List<Ticket> open = new java.util.ArrayList<>(tickets.findCalledAtCounter(counterId));
        open.addAll(tickets.findHeldAtCounter(counterId));
        for (Ticket t : open) {
            Ticket locked = tickets.lockById(t.getId()).orElseThrow();
            Instant originalQueue = locked.getIssuedAt();
            queue.requeue(locked, siteServices.findById(locked.getSiteServiceId()).orElseThrow(),
                    QueueService.Actor.of(user), "Counter closed by supervisor: " + reason);
            locked.setQueuedAt(originalQueue);   // keep the client's original place in line
        }
        close(s);
        audit.record("AGENT_SESSION_FORCE_ENDED", "ServiceCounter", counterId, siteId,
                "agent=" + s.getUserId() + " requeued=" + open.size() + " reason=" + reason);
    }

    private void close(AgentSession s) {
        Instant now = time.now();
        if (s.getStatus() == AgentSession.Status.ON_BREAK) {
            closeBreak(s, now);
        }
        s.setStatus(AgentSession.Status.ENDED);
        s.setEndedAt(now);
        broadcastChange(s.getSiteId());
    }

    private void closeBreak(AgentSession s, Instant now) {
        breaks.findFirstBySessionIdAndEndedAtIsNull(s.getId()).ifPresent(b -> b.setEndedAt(now));
        s.setBreakStartedAt(null);
        s.setBreakReason(null);
    }

    private void broadcastChange(UUID siteId) {
        broadcaster.publishAfterCommit(new QueueDtos.QueueMessage("QUEUE_CHANGED", siteId, null, null, null,
                time.now()));
    }

    private AgentSessionDto toDto(AgentSession s) {
        String counterName = counters.findById(s.getCounterId()).map(ServiceCounter::getName).orElse(null);
        return new AgentSessionDto(s.getId(), s.getCounterId(), counterName, s.getSiteId(), s.getStatus(),
                s.getStartedAt(), s.getBreakStartedAt(), s.getBreakReason());
    }
}

package ph.gov.philhealth.qms.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.catalog.PriorityCategory;
import ph.gov.philhealth.qms.catalog.PriorityCategoryRepository;
import ph.gov.philhealth.qms.catalog.SiteService;
import ph.gov.philhealth.qms.catalog.SiteServiceRepository;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.counter.AgentSession;
import ph.gov.philhealth.qms.counter.AgentSessionRepository;
import ph.gov.philhealth.qms.counter.ServiceCounter;
import ph.gov.philhealth.qms.counter.ServiceCounterRepository;
import ph.gov.philhealth.qms.monitoring.QmsMetrics;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.queue.QueueDtos.QueueMessage;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.sync.OutboxService;
import ph.gov.philhealth.qms.sync.Snapshots;

/**
 * Queue engine: ticket issuance and every ticket status change. All operations run in a single
 * database transaction that locks the affected ticket row, validates the transition against
 * {@link TicketStateMachine}, updates time accumulators, appends a {@link TicketEvent}, writes the
 * sync outbox and, after commit, broadcasts the change.
 */
@Service
public class QueueService {

    private final TicketRepository tickets;
    private final TicketEventRepository events;
    private final TicketNumberAllocator numbers;
    private final SiteRepository sites;
    private final SiteServiceRepository siteServices;
    private final PriorityCategoryRepository priorities;
    private final ServiceCounterRepository counters;
    private final AgentSessionRepository sessions;
    private final QueueBroadcaster broadcaster;
    private final OutboxService outbox;
    private final AuditService audit;
    private final TimeService time;
    private final QmsMetrics metrics;

    public QueueService(TicketRepository tickets, TicketEventRepository events, TicketNumberAllocator numbers,
                        SiteRepository sites, SiteServiceRepository siteServices,
                        PriorityCategoryRepository priorities, ServiceCounterRepository counters,
                        AgentSessionRepository sessions, QueueBroadcaster broadcaster, OutboxService outbox,
                        AuditService audit, TimeService time, QmsMetrics metrics) {
        this.tickets = tickets;
        this.events = events;
        this.numbers = numbers;
        this.sites = sites;
        this.siteServices = siteServices;
        this.priorities = priorities;
        this.counters = counters;
        this.sessions = sessions;
        this.broadcaster = broadcaster;
        this.outbox = outbox;
        this.audit = audit;
        this.time = time;
        this.metrics = metrics;
    }

    /** Who performed an action, for the event log. */
    record Actor(UUID userId, String name) {
        static Actor of(AuthUser u) {
            return new Actor(u.id(), u.fullName());
        }

        static Actor device(DevicePrincipal d) {
            return new Actor(null, "Device " + d.code());
        }

        static final Actor SYSTEM = new Actor(null, "System");
    }

    // ------------------------------------------------------------------ issuance

    /**
     * Issues a ticket at a kiosk. Idempotent on {@code requestId}: a retried request returns the
     * ticket created by the first attempt instead of issuing a second one.
     */
    @Transactional
    public Ticket issue(DevicePrincipal device, UUID siteServiceId, UUID priorityCategoryId, Ticket.Channel channel,
                        UUID requestId) {
        Optional<Ticket> existing = tickets.findByRequestId(requestId);
        if (existing.isPresent()) {
            if (!existing.get().getSiteId().equals(device.siteId())) {
                throw ApiException.conflict("Request id already used");
            }
            return existing.get();
        }
        Site site = sites.findById(device.siteId()).orElseThrow(() -> ApiException.notFound("Site"));
        SiteService service = siteServices.findById(siteServiceId)
                .filter(s -> s.getSiteId().equals(site.getId()))
                .orElseThrow(() -> ApiException.notFound("Service"));
        if (!service.isOpenAt(time.businessDate(site.getTimezone()), time.localTime(site.getTimezone()))) {
            throw ApiException.conflict("This service is not available at the moment");
        }
        int rank = PriorityCategory.REGULAR_RANK;
        if (priorityCategoryId != null) {
            PriorityCategory p = priorities.findById(priorityCategoryId).filter(PriorityCategory::isActive)
                    .orElseThrow(() -> ApiException.badRequest("Unknown priority category"));
            rank = p.getRank();
        }
        Instant now = time.now();
        LocalDate businessDate = time.businessDate(site.getTimezone());
        int seq = numbers.next(site.getId(), service.getPrefix(), businessDate, site.getTicketNumberReset());

        Ticket t = new Ticket();
        t.setId(Ids.newId());
        t.setSiteId(site.getId());
        t.setSiteServiceId(service.getId());
        t.setOriginalSiteServiceId(service.getId());
        t.setBusinessDate(businessDate);
        t.setPrefix(service.getPrefix());
        t.setSeqNo(seq);
        t.setDisplayNumber(TicketNumberAllocator.format(service.getPrefix(), seq, site.getTicketNumberSeparator(),
                site.getTicketNumberDigits()));
        t.setPriorityCategoryId(priorityCategoryId);
        t.setPriorityRank(rank);
        t.setChannel(channel);
        t.setDeviceId(device.deviceId());
        t.setStatus(TicketStatus.WAITING);
        t.setIssuedAt(now);
        t.setQueuedAt(now);
        t.setStatusChangedAt(now);
        t.setRequestId(requestId);
        tickets.saveAndFlush(t);
        recordEvent(t, TicketAction.ISSUE, null, Actor.device(device), null, null);
        String siteCode = site.getCode();
        QmsMetrics.afterCommit(() -> metrics.ticketIssued(siteCode, channel.name()));
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, null));
        return t;
    }

    // ------------------------------------------------------------------ agent actions

    /** Calls the next eligible ticket to the agent's counter, or returns empty when none is waiting. */
    @Transactional
    public Optional<Ticket> callNext() {
        AuthUser user = CurrentUser.get();
        AgentSession session = requireAvailableSession(user);
        if (!tickets.findCalledAtCounter(session.getCounterId()).isEmpty()) {
            throw ApiException.conflict("Complete, hold or transfer the current ticket before calling the next one");
        }
        ServiceCounter counter = counters.findById(session.getCounterId()).orElseThrow();
        Set<UUID> eligible = siteServices.findAllById(counter.getSiteServiceIds()).stream()
                .filter(s -> s.isActive()).map(SiteService::getId).collect(Collectors.toSet());
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        Site site = sites.findById(session.getSiteId()).orElseThrow();
        Optional<Ticket> next = tickets.lockNextWaiting(site.getId(), time.businessDate(site.getTimezone()), eligible);
        next.ifPresent(t -> {
            Instant now = time.now();
            apply(t, TicketAction.CALL, now);
            t.setCounterId(counter.getId());
            t.setAgentUserId(user.id());
            t.setAgentName(user.fullName());
            if (t.getFirstCalledAt() == null) {
                t.setFirstCalledAt(now);
                long queued = java.time.Duration.between(t.getIssuedAt(), now).getSeconds();
                QmsMetrics.afterCommit(() -> metrics.firstCalled(queued));
            }
            t.setLastCalledAt(now);
            recordEvent(t, TicketAction.CALL, TicketStatus.WAITING, Actor.of(user), counter.getId(), null);
            broadcaster.publishAfterCommit(message("TICKET_CALLED", t, counter.getName(), now));
        });
        return next;
    }

    @Transactional
    public Ticket recall(UUID ticketId) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        Instant now = time.now();
        TicketStateMachine.next(TicketAction.RECALL, t.getStatus(), t.getDisplayNumber());
        t.setRecallCount(t.getRecallCount() + 1);
        t.setLastCalledAt(now);
        recordEvent(t, TicketAction.RECALL, TicketStatus.CALLED, Actor.of(user), t.getCounterId(), null);
        broadcaster.publishAfterCommit(message("TICKET_RECALLED", t, counterName(t.getCounterId()), now));
        return t;
    }

    @Transactional
    public Ticket complete(UUID ticketId) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        Instant now = time.now();
        TicketStatus from = apply(t, TicketAction.COMPLETE, now);
        t.setCompletedAt(now);
        recordEvent(t, TicketAction.COMPLETE, from, Actor.of(user), t.getCounterId(), null);
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, now));
        return t;
    }

    @Transactional
    public Ticket hold(UUID ticketId, String reason) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        TicketStatus from = apply(t, TicketAction.HOLD, time.now());
        recordEvent(t, TicketAction.HOLD, from, Actor.of(user), t.getCounterId(), reason);
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, null));
        return t;
    }

    /** Brings a held ticket back to the acting agent's counter. */
    @Transactional
    public Ticket resume(UUID ticketId) {
        AuthUser user = CurrentUser.get();
        AgentSession session = requireAvailableSession(user);
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        if (!tickets.findCalledAtCounter(session.getCounterId()).isEmpty()) {
            throw ApiException.conflict("Finish the current ticket before resuming a held one");
        }
        Instant now = time.now();
        TicketStatus from = apply(t, TicketAction.RESUME, now);
        t.setCounterId(session.getCounterId());
        t.setAgentUserId(user.id());
        t.setAgentName(user.fullName());
        t.setLastCalledAt(now);
        recordEvent(t, TicketAction.RESUME, from, Actor.of(user), session.getCounterId(), null);
        broadcaster.publishAfterCommit(message("TICKET_CALLED", t, counterName(session.getCounterId()), now));
        return t;
    }

    /** Sends the ticket to another service queue at the same site (next stage of a multi-stage service). */
    @Transactional
    public Ticket transfer(UUID ticketId, UUID targetSiteServiceId, String reason) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        SiteService target = siteServices.findById(targetSiteServiceId)
                .filter(s -> s.getSiteId().equals(t.getSiteId()) && s.isActive())
                .orElseThrow(() -> ApiException.badRequest("Target service is not available at this site"));
        return requeue(t, target, Actor.of(user), reason);
    }

    /** Sends the ticket to the next stage configured for its current service (multi-stage flow). */
    @Transactional
    public Ticket nextStage(UUID ticketId) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        SiteService current = siteServices.findById(t.getSiteServiceId()).orElseThrow();
        if (current.getNextSiteServiceId() == null) {
            throw ApiException.conflict(current.getServiceItem().getName() + " has no next stage configured");
        }
        SiteService target = siteServices.findById(current.getNextSiteServiceId())
                .filter(SiteService::isActive)
                .orElseThrow(() -> ApiException.conflict("The next stage service is not active"));
        return requeue(t, target, Actor.of(user), "Proceeds to next stage: " + target.getServiceItem().getName());
    }

    @Transactional
    public Ticket remove(UUID ticketId, String reason) {
        AuthUser user = CurrentUser.get();
        Ticket t = lockForAction(ticketId, user, Permission.QUEUE_SERVE);
        if (t.getStatus() == TicketStatus.WAITING) {
            requireSitePermission(user, t, Permission.QUEUE_SUPERVISE);   // not at anyone's counter yet
        }
        UUID counterId = t.getCounterId();
        TicketStatus from = apply(t, TicketAction.REMOVE, time.now());
        recordEvent(t, TicketAction.REMOVE, from, Actor.of(user), counterId, reason);
        audit.record("TICKET_REMOVED", "Ticket", t.getId(), t.getSiteId(), t.getDisplayNumber() + ": " + reason);
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, null));
        return t;
    }

    @Transactional
    public Ticket voidTicket(UUID ticketId, String reason) {
        AuthUser user = CurrentUser.get();
        Ticket t = tickets.lockById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        requireSitePermission(user, t, Permission.TICKET_VOID);
        UUID counterId = t.getCounterId();
        TicketStatus from = apply(t, TicketAction.VOID, time.now());
        recordEvent(t, TicketAction.VOID, from, Actor.of(user), counterId, reason);
        audit.record("TICKET_VOIDED", "Ticket", t.getId(), t.getSiteId(), t.getDisplayNumber() + ": " + reason);
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, null));
        return t;
    }

    /** Ends open tickets left over from previous business days. Returns the number expired. */
    @Transactional
    public int expireStale(Site site) {
        LocalDate today = time.businessDate(site.getTimezone());
        List<Ticket> stale = tickets.findOpenBefore(site.getId(), today,
                List.of(TicketStatus.WAITING, TicketStatus.CALLED, TicketStatus.ON_HOLD));
        Instant now = time.now();
        for (Ticket t : stale) {
            UUID counterId = t.getCounterId();
            TicketStatus from = apply(t, TicketAction.EXPIRE, now);
            recordEvent(t, TicketAction.EXPIRE, from, Actor.SYSTEM, counterId, "End of business day");
        }
        return stale.size();
    }

    /** Returns a ticket to the waiting line of {@code target}; used by transfers and supervisor intervention. */
    Ticket requeue(Ticket t, SiteService target, Actor actor, String reason) {
        Instant now = time.now();
        UUID fromCounter = t.getCounterId();
        TicketStatus from = apply(t, TicketAction.TRANSFER, now);
        boolean changesService = !target.getId().equals(t.getSiteServiceId());
        t.setSiteServiceId(target.getId());
        if (changesService) {
            t.setStageNo(t.getStageNo() + 1);
        }
        t.setTransferCount(t.getTransferCount() + 1);
        t.setQueuedAt(now);
        t.setCounterId(null);
        t.setAgentUserId(null);
        t.setAgentName(null);
        TicketEvent e = recordEvent(t, TicketAction.TRANSFER, from, actor, fromCounter, reason);
        e.setSiteServiceId(target.getId());
        broadcaster.publishAfterCommit(message("QUEUE_CHANGED", t, null, null));
        return t;
    }

    // ------------------------------------------------------------------ helpers

    private TicketStatus apply(Ticket t, TicketAction action, Instant now) {
        TicketStatus from = t.getStatus();
        TicketStatus to = TicketStateMachine.next(action, from, t.getDisplayNumber());
        t.accumulateUntil(now);
        t.setStatus(to);   // counter and agent are kept on terminal tickets for traceability
        return from;
    }

    /**
     * Loads and row-locks a ticket and checks the actor may act on it: the agent at whose counter the
     * ticket is, or a supervisor of the site.
     */
    private Ticket lockForAction(UUID ticketId, AuthUser user, Permission agentPermission) {
        Ticket t = tickets.lockById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        Site site = sites.findById(t.getSiteId()).orElseThrow();
        if (user.canAccessSite(Permission.QUEUE_SUPERVISE, site.getId(), site.getRegionId())) {
            return t;
        }
        if (!user.canAccessSite(agentPermission, site.getId(), site.getRegionId())) {
            throw ApiException.forbidden("Not allowed to act on tickets at this site");
        }
        Optional<AgentSession> session = sessions.findOpenByUser(user.id());
        if (session.isEmpty() || t.getCounterId() == null || !t.getCounterId().equals(session.get().getCounterId())) {
            throw ApiException.forbidden("Ticket " + t.getDisplayNumber() + " is not assigned to your counter");
        }
        return t;
    }

    private void requireSitePermission(AuthUser user, Ticket t, Permission p) {
        Site site = sites.findById(t.getSiteId()).orElseThrow();
        if (!user.canAccessSite(p, site.getId(), site.getRegionId())) {
            throw ApiException.forbidden("Missing " + p + " for this site");
        }
    }

    private AgentSession requireAvailableSession(AuthUser user) {
        AgentSession s = sessions.findOpenByUser(user.id())
                .orElseThrow(() -> ApiException.conflict("Start a counter session first"));
        if (s.getStatus() != AgentSession.Status.AVAILABLE) {
            throw ApiException.conflict("End your break first");
        }
        Site site = sites.findById(s.getSiteId()).orElseThrow();
        if (!user.canAccessSite(Permission.QUEUE_SERVE, site.getId(), site.getRegionId())) {
            throw ApiException.forbidden("Not allowed to serve at this site");
        }
        return s;
    }

    private TicketEvent recordEvent(Ticket t, TicketAction action, TicketStatus from, Actor actor, UUID counterId,
                                    String reason) {
        TicketEvent e = new TicketEvent();
        e.setId(Ids.newId());
        e.setTicketId(t.getId());
        e.setSiteId(t.getSiteId());
        e.setEventType(action);
        e.setFromStatus(from);
        e.setToStatus(t.getStatus());
        e.setAt(t.getStatusChangedAt() != null && action != TicketAction.RECALL ? t.getStatusChangedAt() : time.now());
        e.setActorUserId(actor.userId());
        e.setActorName(actor.name());
        e.setCounterId(counterId);
        e.setSiteServiceId(t.getSiteServiceId());
        e.setReason(reason);
        events.save(e);
        QmsMetrics.afterCommit(() -> metrics.ticketAction(action.name()));
        // Flush the ticket first so its version in the snapshot matches the committed row.
        tickets.flush();
        outbox.enqueue("TICKET_EVENT", "Ticket", t.getId().toString(), new Snapshots.TicketChange(
                Snapshots.of(t), Snapshots.of(e)));
        return e;
    }

    private String counterName(UUID counterId) {
        return counterId == null ? null : counters.findById(counterId).map(ServiceCounter::getName).orElse(null);
    }

    private QueueMessage message(String type, Ticket t, String counterName, Instant at) {
        String serviceName = siteServices.findById(t.getSiteServiceId())
                .map(s -> s.getServiceItem().getName()).orElse(null);
        return new QueueMessage(type, t.getSiteId(), t.getDisplayNumber(), counterName, serviceName,
                at != null ? at : time.now());
    }
}

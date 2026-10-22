package ph.gov.philhealth.qms.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import ph.gov.philhealth.qms.catalog.SiteService;
import ph.gov.philhealth.qms.counter.Device;
import ph.gov.philhealth.qms.counter.ServiceCounter;
import ph.gov.philhealth.qms.queue.Feedback;
import ph.gov.philhealth.qms.queue.Ticket;
import ph.gov.philhealth.qms.queue.TicketEvent;

/** Versioned, self-contained payloads replicated from site nodes to the central node. */
public final class Snapshots {

    private Snapshots() {
    }

    public record SiteServiceSnapshot(UUID id, UUID siteId, UUID serviceItemId, String prefix, boolean active,
                                      int sortOrder, int estimatedDurationMinutes, LocalTime openTime,
                                      LocalTime closeTime, UUID nextSiteServiceId) {
    }

    public record CounterSnapshot(UUID id, UUID siteId, String code, String name, boolean active,
                                  Set<UUID> siteServiceIds) {
    }

    public record DeviceSnapshot(UUID id, UUID siteId, String code, String name, String type, boolean active) {
    }

    public record TicketSnapshot(UUID id, UUID siteId, UUID siteServiceId, UUID originalSiteServiceId,
                                 LocalDate businessDate, String prefix, int seqNo, String displayNumber,
                                 UUID priorityCategoryId, int priorityRank, String channel, UUID deviceId,
                                 String status, int stageNo, UUID counterId, UUID agentUserId, String agentName,
                                 Instant issuedAt, Instant queuedAt, Instant firstCalledAt, Instant lastCalledAt,
                                 Instant completedAt, Instant statusChangedAt, int recallCount, int transferCount,
                                 long waitSeconds, long serviceSeconds, long holdSeconds, long version) {
    }

    public record TicketEventSnapshot(UUID id, UUID ticketId, UUID siteId, String eventType, String fromStatus,
                                      String toStatus, Instant at, UUID actorUserId, String actorName, UUID counterId,
                                      UUID siteServiceId, String reason) {
    }

    public record TicketChange(TicketSnapshot ticket, TicketEventSnapshot event) {
    }

    public record FeedbackSnapshot(UUID id, UUID ticketId, UUID siteId, short rating, String comment,
                                   Instant submittedAt) {
    }

    public static SiteServiceSnapshot of(SiteService s) {
        return new SiteServiceSnapshot(s.getId(), s.getSiteId(), s.getServiceItem().getId(), s.getPrefix(),
                s.isActive(), s.getSortOrder(), s.getEstimatedDurationMinutes(), s.getOpenTime(), s.getCloseTime(),
                s.getNextSiteServiceId());
    }

    public static CounterSnapshot of(ServiceCounter c) {
        return new CounterSnapshot(c.getId(), c.getSiteId(), c.getCode(), c.getName(), c.isActive(),
                Set.copyOf(c.getSiteServiceIds()));
    }

    public static DeviceSnapshot of(Device d) {
        return new DeviceSnapshot(d.getId(), d.getSiteId(), d.getCode(), d.getName(), d.getType().name(), d.isActive());
    }

    public static TicketSnapshot of(Ticket t) {
        return new TicketSnapshot(t.getId(), t.getSiteId(), t.getSiteServiceId(), t.getOriginalSiteServiceId(),
                t.getBusinessDate(), t.getPrefix(), t.getSeqNo(), t.getDisplayNumber(), t.getPriorityCategoryId(),
                t.getPriorityRank(), t.getChannel().name(), t.getDeviceId(), t.getStatus().name(), t.getStageNo(),
                t.getCounterId(), t.getAgentUserId(), t.getAgentName(), t.getIssuedAt(), t.getQueuedAt(),
                t.getFirstCalledAt(), t.getLastCalledAt(), t.getCompletedAt(), t.getStatusChangedAt(),
                t.getRecallCount(), t.getTransferCount(), t.getWaitSeconds(), t.getServiceSeconds(),
                t.getHoldSeconds(), t.getVersion());
    }

    public static TicketEventSnapshot of(TicketEvent e) {
        return new TicketEventSnapshot(e.getId(), e.getTicketId(), e.getSiteId(), e.getEventType().name(),
                e.getFromStatus() != null ? e.getFromStatus().name() : null, e.getToStatus().name(), e.getAt(),
                e.getActorUserId(), e.getActorName(), e.getCounterId(), e.getSiteServiceId(), e.getReason());
    }

    public static FeedbackSnapshot of(Feedback f) {
        return new FeedbackSnapshot(f.getId(), f.getTicketId(), f.getSiteId(), f.getRating(), f.getComment(),
                f.getSubmittedAt());
    }
}

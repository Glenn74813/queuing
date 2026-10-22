package ph.gov.philhealth.qms.queue;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ph.gov.philhealth.qms.cms.ContentDtos.ContentDto;
import ph.gov.philhealth.qms.counter.AgentSession;
import ph.gov.philhealth.qms.counter.CounterDtos.CounterDto;

public final class QueueDtos {

    private QueueDtos() {
    }

    public record TicketDto(UUID id, String displayNumber, LocalDate businessDate, UUID siteId, UUID siteServiceId,
                            String serviceName, String priorityCode, String priorityName, Ticket.Channel channel,
                            TicketStatus status, int stageNo, UUID counterId, String counterName, String agentName,
                            Instant issuedAt, Instant firstCalledAt, Instant lastCalledAt, Instant completedAt,
                            int recallCount, UUID nextSiteServiceId, String nextServiceName) {
    }

    public record TicketEventDto(UUID id, TicketAction type, TicketStatus fromStatus, TicketStatus toStatus,
                                 Instant at, String actorName, String counterName, String reason,
                                 String siteServiceName) {
    }

    public record TicketDetail(@JsonUnwrapped TicketDto ticket, List<TicketEventDto> events) {
    }

    public record IssuedTicket(@JsonUnwrapped TicketDto ticket, String siteName, long waitingAhead, int estimatedWaitMinutes) {
    }

    public record AgentSessionDto(UUID id, UUID counterId, String counterName, UUID siteId,
                                  AgentSession.Status status, Instant startedAt, Instant breakStartedAt,
                                  String breakReason) {
    }

    public record ServiceLoad(UUID siteServiceId, String serviceName, long waitingCount, int estimatedWaitMinutes) {
    }

    public record QueueStats(long issuedToday, long completedToday, long waitingCount, Double avgWaitMinutesToday) {
    }

    public record QueueSnapshot(UUID siteId, LocalDate businessDate, List<TicketDto> waiting, List<TicketDto> called,
                                List<TicketDto> held, List<CounterDto> counters, List<ServiceLoad> services,
                                QueueStats stats) {
    }

    /** Clients per service at a site over a recent period (Dashboard "Services availed"). */
    public record ServiceVolume(UUID siteServiceId, String serviceCode, String serviceName, long issued, long served,
                                long abandoned, long waiting) {
    }

    public record ServiceVolumeReport(UUID siteId, LocalDate from, LocalDate to, List<ServiceVolume> services) {
    }

    /** Today's figures for one site in the national/regional overview. */
    public record SiteOverview(UUID siteId, String siteCode, String siteName, String regionName, long issuedToday,
                               long completedToday, long waitingNow, Double avgWaitMinutesToday, long countersStaffed,
                               long countersTotal) {
    }

    /** Clients per national catalog service across all sites in scope. */
    public record CatalogServiceVolume(UUID serviceItemId, String serviceCode, String serviceName, long issued,
                                       long served, long abandoned, long waiting, long sitesOffering) {
    }

    /** Dashboard across every site the user may view (all sites for national roles). */
    public record Overview(boolean national, int siteCount, QueueStats totals, List<SiteOverview> sites,
                           LocalDate from, LocalDate to, List<CatalogServiceVolume> services) {
    }

    public record SiteRef(UUID id, String code, String name) {
    }

    public record CallEntry(UUID ticketId, String displayNumber, String counterName, String serviceName,
                            Instant calledAt, int recallCount) {
    }

    public record DisplayBoard(SiteRef site, List<CallEntry> nowServing, List<CallEntry> recentCalls,
                               long waitingCount, List<ContentDto> content, Instant serverTime) {
    }

    public record KioskService(UUID siteServiceId, String name, String description, String prefix, boolean open,
                               long waitingCount, int estimatedWaitMinutes) {
    }

    public record KioskPriority(UUID id, String code, String name, String description) {
    }

    public record KioskConfig(SiteRef site, List<KioskService> services, List<KioskPriority> priorityCategories,
                              List<ContentDto> content) {
    }

    // ------------------------------------------------------------------ requests

    public record StartSessionRequest(@NotNull UUID counterId) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record OptionalReasonRequest(@Size(max = 500) String reason) {
    }

    public record TransferRequest(@NotNull UUID targetSiteServiceId, @NotBlank @Size(max = 500) String reason) {
    }

    public record IssueTicketRequest(@NotNull UUID siteServiceId, UUID priorityCategoryId,
                                     @NotNull Ticket.Channel channel, @NotNull UUID requestId) {
    }

    public record FeedbackRequest(@NotNull UUID ticketId, @Min(1) @Max(5) int rating,
                                  @Size(max = 1000) String comment) {
    }

    /** WebSocket broadcast; deliberately carries no personal data. */
    public record QueueMessage(String type, UUID siteId, String displayNumber, String counterName,
                               String serviceName, Instant at) {
    }
}

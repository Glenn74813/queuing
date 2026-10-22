package ph.gov.philhealth.qms.queue;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A client's queue ticket. Time spent in each status is accumulated on every transition
 * ({@link #accumulateUntil}) so reports do not have to replay the event log.
 */
@Entity
@Table(name = "ticket")
@Getter
@Setter
public class Ticket {

    public enum Channel { PRINTED, ELECTRONIC }

    @Id
    private UUID id;
    private UUID siteId;
    private UUID siteServiceId;
    private UUID originalSiteServiceId;
    private LocalDate businessDate;
    private String prefix;
    private int seqNo;
    private String displayNumber;
    private UUID priorityCategoryId;
    private int priorityRank;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    private int stageNo = 1;
    private UUID counterId;
    private UUID agentUserId;
    private String agentName;
    private Instant issuedAt;
    private Instant queuedAt;
    private Instant firstCalledAt;
    private Instant lastCalledAt;
    private Instant completedAt;
    private Instant statusChangedAt;
    private int recallCount;
    private int transferCount;
    private long waitSeconds;
    private long serviceSeconds;
    private long holdSeconds;
    private UUID requestId;

    @Version
    private long version;

    /** Adds the time spent in the current status (since the last transition) to its accumulator. */
    public void accumulateUntil(Instant now) {
        long secs = Math.max(0, Duration.between(statusChangedAt, now).getSeconds());
        switch (status) {
            case WAITING -> waitSeconds += secs;
            case CALLED -> serviceSeconds += secs;
            case ON_HOLD -> holdSeconds += secs;
            default -> { }
        }
        statusChangedAt = now;
    }
}

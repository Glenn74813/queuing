package ph.gov.philhealth.qms.sync;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sync_outbox")
@Getter
@Setter
public class OutboxEntry {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    private UUID id;
    private String idempotencyKey;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String siteCode;
    private String payload;
    private String payloadSha256;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant createdAt;
    private Instant sentAt;
}

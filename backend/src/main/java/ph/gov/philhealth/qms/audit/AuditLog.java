package ph.gov.philhealth.qms.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Append-only audit record. The application never updates or deletes rows in this table. */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {
    @Id
    private UUID id;
    private Instant at;
    private UUID actorUserId;
    private String actorUsername;
    private String action;
    private String entityType;
    private String entityId;
    private UUID siteId;
    private String details;
    private String ipAddress;
}

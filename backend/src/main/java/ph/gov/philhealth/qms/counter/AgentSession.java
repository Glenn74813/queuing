package ph.gov.philhealth.qms.counter;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** An agent signed in at a counter. At most one open session per agent and per counter (DB-enforced). */
@Entity
@Table(name = "agent_session")
@Getter
@Setter
public class AgentSession {

    public enum Status { AVAILABLE, ON_BREAK, ENDED }

    @Id
    private UUID id;
    private UUID userId;
    private UUID counterId;
    private UUID siteId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant startedAt;
    private Instant endedAt;
    private Instant breakStartedAt;
    private String breakReason;

    @Version
    private long version;
}

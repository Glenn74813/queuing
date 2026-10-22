package ph.gov.philhealth.qms.counter;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agent_break")
@Getter
@Setter
public class AgentBreak {
    @Id
    private UUID id;
    private UUID sessionId;
    private Instant startedAt;
    private Instant endedAt;
    private String reason;
}

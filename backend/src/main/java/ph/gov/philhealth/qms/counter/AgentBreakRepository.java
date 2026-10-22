package ph.gov.philhealth.qms.counter;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentBreakRepository extends JpaRepository<AgentBreak, UUID> {
    Optional<AgentBreak> findFirstBySessionIdAndEndedAtIsNull(UUID sessionId);
}

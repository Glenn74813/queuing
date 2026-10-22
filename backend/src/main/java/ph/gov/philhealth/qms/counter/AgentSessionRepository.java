package ph.gov.philhealth.qms.counter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    @Query("select s from AgentSession s where s.userId = :userId and s.status <> ph.gov.philhealth.qms.counter.AgentSession.Status.ENDED")
    Optional<AgentSession> findOpenByUser(@Param("userId") UUID userId);

    @Query("select s from AgentSession s where s.siteId = :siteId and s.status <> ph.gov.philhealth.qms.counter.AgentSession.Status.ENDED")
    List<AgentSession> findOpenBySite(@Param("siteId") UUID siteId);
}

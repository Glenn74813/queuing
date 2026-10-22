package ph.gov.philhealth.qms.counter;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceCounterRepository extends JpaRepository<ServiceCounter, UUID> {
    List<ServiceCounter> findBySiteIdOrderByCodeAsc(UUID siteId);
}

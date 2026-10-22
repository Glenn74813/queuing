package ph.gov.philhealth.qms.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriorityCategoryRepository extends JpaRepository<PriorityCategory, UUID> {
    List<PriorityCategory> findAllByOrderByRankAscCodeAsc();

    List<PriorityCategory> findByUpdatedAtAfter(Instant since);
}

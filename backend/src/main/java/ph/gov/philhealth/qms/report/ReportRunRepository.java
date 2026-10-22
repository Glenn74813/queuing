package ph.gov.philhealth.qms.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRunRepository extends JpaRepository<ReportRun, UUID> {

    List<ReportRun> findTop50ByScheduleIdOrderByGeneratedAtDesc(UUID scheduleId);

    Optional<ReportRun> findFirstByScheduleIdOrderByGeneratedAtDesc(UUID scheduleId);

    List<ReportRun> findByGeneratedAtBeforeAndFileNameIsNotNull(Instant cutoff);
}

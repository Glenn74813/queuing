package ph.gov.philhealth.qms.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, UUID> {

    List<ReportSchedule> findAllByOrderByNameAsc();

    /** Due schedules, locked so that two API instances never generate the same run twice. */
    @Query(value = """
            select * from report_schedule where active and next_run_at <= :now
            order by next_run_at limit 20 for update skip locked
            """, nativeQuery = true)
    List<ReportSchedule> lockDue(@Param("now") Instant now);
}

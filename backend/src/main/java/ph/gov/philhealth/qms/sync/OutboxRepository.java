package ph.gov.philhealth.qms.sync;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Query("""
            select o from OutboxEntry o
            where o.status <> ph.gov.philhealth.qms.sync.OutboxEntry.Status.SENT and o.nextAttemptAt <= :now
            order by o.createdAt
            """)
    List<OutboxEntry> findDue(@Param("now") Instant now, Pageable pageable);

    long countByStatus(OutboxEntry.Status status);

    Optional<OutboxEntry> findFirstByStatusNotOrderByCreatedAtAsc(OutboxEntry.Status status);

    Page<OutboxEntry> findByStatus(OutboxEntry.Status status, Pageable pageable);
}

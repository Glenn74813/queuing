package ph.gov.philhealth.qms.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            select a from AuditLog a
            where a.at >= :from and a.at < :to
              and (:actor = '' or lower(a.actorUsername) = lower(:actor))
              and (:action = '' or a.action = :action)
              and (:allSites = true or a.siteId in :siteIds)
            """)
    /** Pass "" (not null) for an absent filter: PostgreSQL cannot type a null string parameter. */
    Page<AuditLog> search(@Param("from") Instant from, @Param("to") Instant to, @Param("actor") String actor,
                          @Param("action") String action, @Param("allSites") boolean allSites,
                          @Param("siteIds") Collection<UUID> siteIds, Pageable pageable);
}

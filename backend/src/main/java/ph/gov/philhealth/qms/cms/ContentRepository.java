package ph.gov.philhealth.qms.cms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRepository extends JpaRepository<ContentItem, UUID> {

    /** Published content currently in its schedule window that targets the site, its region, or everyone. */
    @Query("""
            select c from ContentItem c
            where c.status = ph.gov.philhealth.qms.cms.ContentItem.Status.PUBLISHED
              and (c.startsAt is null or c.startsAt <= :now) and (c.endsAt is null or c.endsAt > :now)
              and (c.siteId = :siteId or (c.siteId is null and (c.regionId = :regionId or c.regionId is null)))
            order by c.updatedAt desc
            """)
    List<ContentItem> findActive(@Param("siteId") UUID siteId, @Param("regionId") UUID regionId,
                                 @Param("now") Instant now);

    List<ContentItem> findAllByOrderByUpdatedAtDesc();
}

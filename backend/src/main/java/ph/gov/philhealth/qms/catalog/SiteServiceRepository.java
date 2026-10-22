package ph.gov.philhealth.qms.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteServiceRepository extends JpaRepository<SiteService, UUID> {
    List<SiteService> findBySiteIdOrderBySortOrderAsc(UUID siteId);
}

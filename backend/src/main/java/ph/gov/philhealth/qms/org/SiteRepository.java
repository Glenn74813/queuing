package ph.gov.philhealth.qms.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, UUID> {
    List<Site> findAllByOrderByCodeAsc();

    List<Site> findByRegionIdOrderByCodeAsc(UUID regionId);

    Optional<Site> findByCode(String code);
}

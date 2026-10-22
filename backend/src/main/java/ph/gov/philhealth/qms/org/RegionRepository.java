package ph.gov.philhealth.qms.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, UUID> {
    List<Region> findAllByOrderByCodeAsc();

    Optional<Region> findByCode(String code);
}

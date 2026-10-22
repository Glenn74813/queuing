package ph.gov.philhealth.qms.sla;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {
    Optional<SlaPolicy> findByRegionId(UUID regionId);

    @Query("select p from SlaPolicy p where p.regionId is null")
    Optional<SlaPolicy> findNational();
}

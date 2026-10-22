package ph.gov.philhealth.qms.counter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByApiKeyHash(String apiKeyHash);

    List<Device> findBySiteIdOrderByCodeAsc(UUID siteId);
}

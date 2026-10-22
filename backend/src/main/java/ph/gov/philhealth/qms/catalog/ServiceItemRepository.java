package ph.gov.philhealth.qms.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {
    List<ServiceItem> findAllByOrderBySortOrderAscCodeAsc();

    List<ServiceItem> findByUpdatedAtAfter(Instant since);
}

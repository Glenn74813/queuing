package ph.gov.philhealth.qms.sync;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncState, String> {
}

package ph.gov.philhealth.qms.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppRoleRepository extends JpaRepository<AppRole, String> {
    List<AppRole> findAllByOrderByLevelDesc();
}

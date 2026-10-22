package ph.gov.philhealth.qms.user;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.org.Region;
import ph.gov.philhealth.qms.org.Site;

@Entity
@Table(name = "user_role_assignment")
@Getter
@Setter
public class RoleAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_code")
    private AppRole role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "region_id")
    private Region region;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "site_id")
    private Site site;
}

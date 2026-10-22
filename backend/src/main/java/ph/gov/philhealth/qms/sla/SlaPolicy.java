package ph.gov.philhealth.qms.sla;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

/** Service level targets for one region (or the national default when {@code regionId} is null). */
@Entity
@Table(name = "sla_policy")
@Getter
@Setter
public class SlaPolicy extends BaseEntity {
    private UUID regionId;
    private int waitingTargetMinutes;
    private int processingTargetMinutes;
    private BigDecimal complianceTargetPercent;
    private int version = 1;
    private String updatedBy;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<SlaServiceTarget> serviceTargets = new ArrayList<>();
}

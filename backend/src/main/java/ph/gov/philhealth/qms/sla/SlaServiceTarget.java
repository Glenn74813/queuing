package ph.gov.philhealth.qms.sla;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Service-specific override of a policy's targets; null fields fall back to the policy. */
@Entity
@Table(name = "sla_service_target")
@Getter
@Setter
public class SlaServiceTarget {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private SlaPolicy policy;

    private UUID serviceItemId;
    private Integer waitingTargetMinutes;
    private Integer processingTargetMinutes;
}

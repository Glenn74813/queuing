package ph.gov.philhealth.qms.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

@Entity
@Table(name = "priority_category")
@Getter
@Setter
public class PriorityCategory extends BaseEntity {

    /** Rank used for tickets without a priority category. */
    public static final int REGULAR_RANK = 100;

    private String code;
    private String name;
    private String description;
    private int rank;
    private boolean active = true;
}

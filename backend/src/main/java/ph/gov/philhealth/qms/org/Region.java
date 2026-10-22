package ph.gov.philhealth.qms.org;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

@Entity
@Table(name = "region")
@Getter
@Setter
public class Region extends BaseEntity {
    private String code;
    private String name;
    private boolean active = true;
}

package ph.gov.philhealth.qms.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

@Entity
@Table(name = "site")
@Getter
@Setter
public class Site extends BaseEntity {

    public enum Type { CENTRAL_OFFICE, REGIONAL_OFFICE, LHIO }

    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    private Type type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id")
    private Region region;

    @Column(name = "region_id", insertable = false, updatable = false)
    private UUID regionId;

    private String area;
    private String timezone = "Asia/Manila";
    private boolean active = true;
}

package ph.gov.philhealth.qms.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

/** National catalog entry; sites enable it through {@link SiteService}. */
@Entity
@Table(name = "service_item")
@Getter
@Setter
public class ServiceItem extends BaseEntity {

    public enum Classification { SIMPLE, COMPLEX, HIGHLY_TECHNICAL_A, HIGHLY_TECHNICAL_B, OTHER }

    public enum ClientType { G2C, G2B, G2G }

    public enum ServiceType { INTERNAL, EXTERNAL }

    private String code;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private Classification classification;

    @Enumerated(EnumType.STRING)
    private ClientType clientType;

    @Enumerated(EnumType.STRING)
    private ServiceType serviceType;

    private boolean multiStage;
    private String defaultPrefix;
    private int defaultDurationMinutes;
    private String requiredDocuments;
    private String eligibilityNotes;
    private boolean active = true;
    private int sortOrder;
    /** Incremented on every change; the audit log keeps before/after values of each version. */
    private int version = 1;
    private LocalDate effectiveFrom;
    /** First day the service is no longer offered (exclusive end); null = open-ended. */
    private LocalDate effectiveTo;

    public boolean isEffectiveOn(LocalDate date) {
        return (effectiveFrom == null || !date.isBefore(effectiveFrom))
                && (effectiveTo == null || date.isBefore(effectiveTo));
    }
}

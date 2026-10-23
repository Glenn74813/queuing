package ph.gov.philhealth.qms.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;
import ph.gov.philhealth.qms.common.BaseEntity;

/** A catalog service offered at one site, with site-specific prefix, ordering and hours. */
@Entity
@Table(name = "site_service")
@Getter
@Setter
public class SiteService extends BaseEntity {

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "service_item_id")
    private ServiceItem serviceItem;

    private String prefix;
    private boolean active = true;
    private int sortOrder;
    private int estimatedDurationMinutes;
    @JdbcType(LocalTimeJdbcType.class)   // wall-clock time: no JVM/UTC time-zone shift
    private LocalTime openTime;
    @JdbcType(LocalTimeJdbcType.class)
    private LocalTime closeTime;
    /** Default next stage of a multi-stage flow at the same site (null = last stage). */
    private UUID nextSiteServiceId;

    /**
     * Open when the site service and catalog item are active, the catalog item is effective on the
     * business date, and the local time is inside the configured hours (if any).
     */
    public boolean isOpenAt(LocalDate date, LocalTime now) {
        if (!active || !serviceItem.isActive() || !serviceItem.isEffectiveOn(date)) {
            return false;
        }
        if (openTime != null && now.isBefore(openTime)) {
            return false;
        }
        return closeTime == null || now.isBefore(closeTime);
    }
}

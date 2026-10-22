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
import ph.gov.philhealth.qms.queue.TicketNumberAllocator;

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

    /** Zero-padded width of the number part of a ticket (A-007 is 3). */
    private int ticketNumberDigits = 3;
    /** Between prefix and number: "-" or none. */
    private String ticketNumberSeparator = "-";

    @Enumerated(EnumType.STRING)
    private TicketNumberAllocator.Reset ticketNumberReset = TicketNumberAllocator.Reset.DAILY;
}

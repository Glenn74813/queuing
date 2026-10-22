package ph.gov.philhealth.qms.counter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

/** A kiosk (ticket dispenser) or a queue display, authenticated with an API key. */
@Entity
@Table(name = "device")
@Getter
@Setter
public class Device extends BaseEntity {

    public enum Type { KIOSK, DISPLAY }

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String apiKeyHash;
    private boolean active = true;
    private Instant lastSeenAt;
}

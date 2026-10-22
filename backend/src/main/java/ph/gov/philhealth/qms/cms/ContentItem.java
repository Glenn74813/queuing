package ph.gov.philhealth.qms.cms;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

/** Announcement, poster or video shown on kiosks and displays. Site-, region- or nation-wide. */
@Entity
@Table(name = "content_item")
@Getter
@Setter
public class ContentItem extends BaseEntity {

    public enum Type { ANNOUNCEMENT, IMAGE, VIDEO }

    public enum Status { DRAFT, PENDING_APPROVAL, PUBLISHED, REJECTED, ARCHIVED }

    /** LOCAL = created on this node; CENTRAL = replicated from the central node and read-only here. */
    public enum Origin { LOCAL, CENTRAL }

    private String title;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String body;
    private String mediaPath;
    private String mimeType;
    private Long sizeBytes;
    private String mediaSha256;

    @Enumerated(EnumType.STRING)
    private Origin origin = Origin.LOCAL;
    private UUID siteId;
    private UUID regionId;
    private Instant startsAt;
    private Instant endsAt;
    private int displaySeconds = 15;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private String createdBy;
    private String approvedBy;
    private Instant approvedAt;
    private String rejectionReason;

    @Version
    private long version;
}

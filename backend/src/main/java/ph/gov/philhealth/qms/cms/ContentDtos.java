package ph.gov.philhealth.qms.cms;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ContentDtos {

    private ContentDtos() {
    }

    public record ContentDto(UUID id, String title, ContentItem.Type type, String body, String mediaUrl,
                             String mimeType, UUID siteId, UUID regionId, Instant startsAt, Instant endsAt,
                             int displaySeconds, ContentItem.Status status, String createdBy, String approvedBy,
                             String rejectionReason, Instant updatedAt, ContentItem.Origin origin) {
        public static ContentDto of(ContentItem c) {
            return new ContentDto(c.getId(), c.getTitle(), c.getType(), c.getBody(),
                    c.getMediaPath() != null ? "/api/v1/public/media/" + c.getId() : null, c.getMimeType(),
                    c.getSiteId(), c.getRegionId(), c.getStartsAt(), c.getEndsAt(), c.getDisplaySeconds(),
                    c.getStatus(), c.getCreatedBy(), c.getApprovedBy(), c.getRejectionReason(), c.getUpdatedAt(),
                    c.getOrigin());
        }
    }

    public record ContentInput(@NotBlank @Size(max = 200) String title, @NotNull ContentItem.Type type,
                               @Size(max = 5000) String body, UUID siteId, UUID regionId, Instant startsAt,
                               Instant endsAt, @Min(3) @Max(600) int displaySeconds) {
    }
}

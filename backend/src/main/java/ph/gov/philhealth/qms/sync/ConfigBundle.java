package ph.gov.philhealth.qms.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Central-owned configuration distributed to site nodes (central → site direction). */
public record ConfigBundle(Instant serverTime, List<RegionRow> regions, List<SiteRow> sites,
                           List<ServiceItemRow> serviceItems, List<PriorityRow> priorityCategories,
                           List<ContentRow> contents) {

    /** Published (or since archived) national/regional/site content created on central for this site. */
    public record ContentRow(UUID id, String title, String type, String body, String mediaPath, String mimeType,
                             Long sizeBytes, String mediaSha256, UUID siteId, UUID regionId, Instant startsAt,
                             Instant endsAt, int displaySeconds, String status, String createdBy,
                             String approvedBy, Instant approvedAt) {
    }

    public record RegionRow(UUID id, String code, String name, boolean active) {
    }

    /** Numbering fields are null in bundles from a central node older than V11; the site keeps its own then. */
    public record SiteRow(UUID id, String code, String name, String type, UUID regionId, String area, String timezone,
                          boolean active, Integer ticketNumberDigits, String ticketNumberSeparator,
                          String ticketNumberReset) {
    }

    public record ServiceItemRow(UUID id, String code, String name, String description, String classification,
                                 String clientType, String serviceType, boolean multiStage, String defaultPrefix,
                                 int defaultDurationMinutes, String requiredDocuments, String eligibilityNotes,
                                 boolean active, int sortOrder, int version, LocalDate effectiveFrom,
                                 LocalDate effectiveTo) {
    }

    public record PriorityRow(UUID id, String code, String name, String description, int rank, boolean active) {
    }
}

package ph.gov.philhealth.qms.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record ServiceItemDto(UUID id, String code, String name, String description,
                                 ServiceItem.Classification classification, ServiceItem.ClientType clientType,
                                 ServiceItem.ServiceType serviceType, boolean multiStage, String defaultPrefix,
                                 int defaultDurationMinutes, String requiredDocuments, String eligibilityNotes,
                                 boolean active, int sortOrder, int version, LocalDate effectiveFrom,
                                 LocalDate effectiveTo) {
        public static ServiceItemDto of(ServiceItem s) {
            return new ServiceItemDto(s.getId(), s.getCode(), s.getName(), s.getDescription(), s.getClassification(),
                    s.getClientType(), s.getServiceType(), s.isMultiStage(), s.getDefaultPrefix(),
                    s.getDefaultDurationMinutes(), s.getRequiredDocuments(), s.getEligibilityNotes(), s.isActive(),
                    s.getSortOrder(), s.getVersion(), s.getEffectiveFrom(), s.getEffectiveTo());
        }
    }

    public record ServiceItemInput(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Z0-9_-]+$") String code,
            @NotBlank @Size(max = 250) String name,
            @Size(max = 4000) String description,
            @NotNull ServiceItem.Classification classification,
            @NotNull ServiceItem.ClientType clientType,
            @NotNull ServiceItem.ServiceType serviceType,
            boolean multiStage,
            @NotBlank @Pattern(regexp = "^[A-Z]{1,3}$") String defaultPrefix,
            @Min(1) @Max(480) int defaultDurationMinutes,
            @Size(max = 4000) String requiredDocuments,
            @Size(max = 4000) String eligibilityNotes,
            boolean active,
            int sortOrder,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record ServiceItemVersionDto(int version, String snapshot, String changedBy, java.time.Instant changedAt) {
    }

    public record SiteServiceDto(UUID id, UUID siteId, UUID serviceItemId, String serviceCode, String serviceName,
                                 ServiceItem.Classification classification, ServiceItem.ClientType clientType,
                                 ServiceItem.ServiceType serviceType, String prefix, boolean active, int sortOrder,
                                 int estimatedDurationMinutes, LocalTime openTime, LocalTime closeTime,
                                 UUID nextSiteServiceId) {
        public static SiteServiceDto of(SiteService s) {
            ServiceItem i = s.getServiceItem();
            return new SiteServiceDto(s.getId(), s.getSiteId(), i.getId(), i.getCode(), i.getName(),
                    i.getClassification(), i.getClientType(), i.getServiceType(), s.getPrefix(), s.isActive(),
                    s.getSortOrder(), s.getEstimatedDurationMinutes(), s.getOpenTime(), s.getCloseTime(),
                    s.getNextSiteServiceId());
        }
    }

    public record SiteServiceInput(@NotNull UUID serviceItemId,
                                   @NotBlank @Pattern(regexp = "^[A-Z]{1,3}$") String prefix,
                                   boolean active, int sortOrder,
                                   @Min(1) @Max(480) int estimatedDurationMinutes,
                                   LocalTime openTime, LocalTime closeTime, UUID nextSiteServiceId) {
    }

    public record PriorityCategoryDto(UUID id, String code, String name, String description, int rank,
                                      boolean active) {
        public static PriorityCategoryDto of(PriorityCategory p) {
            return new PriorityCategoryDto(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getRank(),
                    p.isActive());
        }
    }

    public record PriorityCategoryInput(@NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Z0-9_]+$") String code,
                                        @NotBlank @Size(max = 100) String name,
                                        @Size(max = 2000) String description,
                                        @Min(0) @Max(99) int rank, boolean active) {
    }
}

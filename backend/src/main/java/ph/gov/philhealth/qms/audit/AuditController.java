package ph.gov.philhealth.qms.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.report.ReportExporter;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    public record AuditEntry(UUID id, Instant at, String actorUsername, String action, String entityType,
                             String entityId, UUID siteId, String details, String ipAddress) {
    }

    private static final int EXPORT_MAX_ROWS = 100_000;

    private final AuditLogRepository repo;
    private final AuditService audit;
    private final ScopeService scope;
    private final ZoneId zone;

    public AuditController(AuditLogRepository repo, ScopeService scope, QmsProperties props, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
        this.scope = scope;
        this.zone = ZoneId.of(props.node().defaultTimezone());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Set<UUID> sites = scope.accessibleSiteIds(Permission.AUDIT_VIEW);
        // Records without a site (national configuration changes) are visible to national auditors only.
        boolean all = sites == null;
        var result = repo.search(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(),
                blankToEmpty(actor), blankToEmpty(action), all, all || sites.isEmpty() ? List.of(UUID.randomUUID()) : sites,
                PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "at")));
        return PageResponse.of(result, a -> new AuditEntry(a.getId(), a.getAt(), a.getActorUsername(), a.getAction(),
                a.getEntityType(), a.getEntityId(), a.getSiteId(), a.getDetails(), a.getIpAddress()));
    }

    /** CSV of the filtered audit trail (same scope rules as the search), capped at 100,000 rows. */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Transactional
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String actor, @RequestParam(required = false) String action) {
        Set<UUID> sites = scope.accessibleSiteIds(Permission.AUDIT_VIEW);
        boolean all = sites == null;
        var page = repo.search(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(),
                blankToEmpty(actor), blankToEmpty(action), all, all || sites.isEmpty() ? List.of(UUID.randomUUID()) : sites,
                PageRequest.of(0, EXPORT_MAX_ROWS, Sort.by(Sort.Direction.ASC, "at")));
        if (page.getTotalElements() > EXPORT_MAX_ROWS) {
            throw ApiException.badRequest("The export would contain " + page.getTotalElements()
                    + " entries; the limit is " + EXPORT_MAX_ROWS + ". Narrow the date range or filters.");
        }
        StringBuilder sb = new StringBuilder("Time (UTC),Actor,Action,Entity type,Entity ID,Site ID,IP address,Details\r\n");
        for (AuditLog a : page.getContent()) {
            sb.append(String.join(",", java.util.stream.Stream.of(String.valueOf(a.getAt()), a.getActorUsername(),
                            a.getAction(), a.getEntityType(), a.getEntityId(),
                            a.getSiteId() == null ? null : a.getSiteId().toString(), a.getIpAddress(), a.getDetails())
                    .map(v -> ReportExporter.csvCell(v == null ? "" : v)).toList())).append("\r\n");
        }
        audit.record("AUDIT_EXPORTED", "AuditLog", null, null,
                "from=%s to=%s actor=%s action=%s rows=%d".formatted(from, to, actor, action, page.getNumberOfElements()));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("qms-audit-%s_%s.csv".formatted(from, to)).build().toString())
                .body(ReportExporter.withBom(sb.toString()));
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }
}

package ph.gov.philhealth.qms.sync;

import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.cms.MediaStorage;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

@RestController
public class SyncController {

    public record OutboxDto(UUID id, String idempotencyKey, String eventType, String aggregateType,
                            String aggregateId, OutboxEntry.Status status, int attempts, Instant nextAttemptAt,
                            String lastError, Instant createdAt, Instant sentAt) {
        static OutboxDto of(OutboxEntry e) {
            return new OutboxDto(e.getId(), e.getIdempotencyKey(), e.getEventType(), e.getAggregateType(),
                    e.getAggregateId(), e.getStatus(), e.getAttempts(), e.getNextAttemptAt(), e.getLastError(),
                    e.getCreatedAt(), e.getSentAt());
        }
    }

    public record OutboxSummary(long pending, long failed, Instant oldestPendingAt) {
    }

    public record SystemStatus(QmsProperties.NodeRole nodeRole, String siteCode, String centralUrl, String version,
                               OutboxSummary outbox, String lastSuccessfulPushAt, String lastConfigPullAt,
                               Instant serverTime) {
    }

    private final OutboxRepository outbox;
    private final SyncIngestService ingest;
    private final ConfigSyncService config;
    private final SyncStateService state;
    private final SiteKeyService siteKeys;
    private final ReconciliationService reconciliation;
    private final MediaStorage mediaStorage;
    private final ScopeService scope;
    private final AuditService audit;
    private final QmsProperties props;
    private final String version;

    public SyncController(OutboxRepository outbox, SyncIngestService ingest, ConfigSyncService config,
                          SyncStateService state, SiteKeyService siteKeys, ReconciliationService reconciliation,
                          MediaStorage mediaStorage,
                          ScopeService scope, AuditService audit,
                          QmsProperties props,
                          org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> build) {
        this.outbox = outbox;
        this.ingest = ingest;
        this.config = config;
        this.state = state;
        this.siteKeys = siteKeys;
        this.reconciliation = reconciliation;
        this.mediaStorage = mediaStorage;
        this.scope = scope;
        this.audit = audit;
        this.props = props;
        var b = build.getIfAvailable();
        this.version = b != null ? b.getVersion() : "dev";
    }

    @GetMapping("/api/v1/system/status")
    @PreAuthorize("hasAuthority('SYSTEM_MONITOR')")
    @Transactional(readOnly = true)
    public SystemStatus status() {
        var oldest = outbox.findFirstByStatusNotOrderByCreatedAtAsc(OutboxEntry.Status.SENT);
        return new SystemStatus(props.node().role(), props.node().siteCode(), props.sync().centralUrl(), version,
                new OutboxSummary(outbox.countByStatus(OutboxEntry.Status.PENDING),
                        outbox.countByStatus(OutboxEntry.Status.FAILED),
                        oldest.map(OutboxEntry::getCreatedAt).orElse(null)),
                state.get(SyncStateService.LAST_PUSH).orElse(null),
                state.get(SyncStateService.LAST_CONFIG_PULL).orElse(null), Instant.now());
    }

    @GetMapping("/api/v1/sync/outbox")
    @PreAuthorize("hasAuthority('SYNC_MANAGE')")
    @Transactional(readOnly = true)
    public PageResponse<OutboxDto> outbox(@RequestParam(required = false) OutboxEntry.Status status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        var req = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = status == null ? outbox.findAll(req) : outbox.findByStatus(status, req);
        return PageResponse.of(result, OutboxDto::of);
    }

    @PostMapping("/api/v1/sync/outbox/{id}/retry")
    @PreAuthorize("hasAuthority('SYNC_MANAGE')")
    @Transactional
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        OutboxEntry e = outbox.findById(id).orElseThrow(() -> ApiException.notFound("Outbox entry"));
        if (e.getStatus() == OutboxEntry.Status.SENT) {
            throw ApiException.conflict("Entry was already delivered");
        }
        e.setNextAttemptAt(Instant.now());
        audit.record("SYNC_RETRY_REQUESTED", "OutboxEntry", id, null, e.getIdempotencyKey());
        return ResponseEntity.noContent().build();
    }

    /** Central only: issue or rotate the key a site node uses to push data. */
    @PostMapping("/api/v1/sites/{siteId}/sync-key")
    @PreAuthorize("hasAuthority('SYNC_MANAGE')")
    @Transactional
    public Map<String, String> issueSiteKey(@PathVariable UUID siteId) {
        scope.requireNational(Permission.SYNC_MANAGE);
        if (!props.node().isCentral()) {
            throw ApiException.forbidden("Site keys are issued on the central node");
        }
        String key = siteKeys.issue(siteId);
        audit.record("SYNC_SITE_KEY_ISSUED", "Site", siteId, siteId, "by " + CurrentUser.get().username());
        return Map.of("siteKey", key);
    }

    /** Central only: site-reported daily counts compared with what central received. */
    @GetMapping("/api/v1/sync/reconciliation")
    @PreAuthorize("hasAuthority('SYNC_MANAGE')")
    public List<ReconciliationService.Row> reconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean onlyProblems) {
        scope.requireNational(Permission.SYNC_MANAGE);
        return reconciliation.report(from, to, onlyProblems);
    }

    /** Site only: queue daily summaries now instead of waiting for the hourly job. */
    @PostMapping("/api/v1/sync/reconciliation/report-now")
    @PreAuthorize("hasAuthority('SYNC_MANAGE')")
    public ResponseEntity<Void> reportNow() {
        if (props.node().isCentral() || !props.sync().enabled()) {
            throw ApiException.conflict("Only a synchronized site node sends reconciliation summaries");
        }
        reconciliation.reportRecentDays();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/sync/ingest")
    public SyncEnvelope.Result ingest(@Valid @RequestBody SyncEnvelope.Batch batch) {
        if (batch.entries() == null || batch.entries().size() > 1000) {
            throw ApiException.badRequest("Batch must contain 1-1000 entries");
        }
        return ingest.ingest(CurrentUser.site(), batch.entries());
    }

    @GetMapping("/api/v1/sync/config")
    public ConfigBundle config(@RequestParam(required = false) Instant since) {
        CurrentUser.site();
        if (!props.node().isCentral()) {
            throw ApiException.forbidden("Configuration is distributed by the central node");
        }
        return config.bundle(since, CurrentUser.site());
    }

    /** Central only: media of published content distributed to the calling site. */
    @GetMapping("/api/v1/sync/media/{contentId}")
    public ResponseEntity<Resource> media(@PathVariable UUID contentId) {
        if (!props.node().isCentral()) {
            throw ApiException.forbidden("Media is distributed by the central node");
        }
        String stored = config.distributableMedia(contentId, CurrentUser.site());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(mediaStorage.resolve(stored)));
    }
}

package ph.gov.philhealth.qms.sync;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import ph.gov.philhealth.qms.cms.MediaStorage;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.security.SiteKeyAuthenticationFilter;
import ph.gov.philhealth.qms.security.SitePrincipal;

/**
 * Builds configuration bundles on the central node and applies them on site nodes. Pulls are
 * incremental using the central server's clock as watermark (with a 5-minute overlap so rows
 * committed late are not missed; applying a row twice is harmless because it is an upsert).
 */
@Service
public class ConfigSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSyncService.class);
    private static final Duration OVERLAP = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final QmsProperties props;
    private final SyncStateService state;
    private final TransactionTemplate tx;
    private final MediaStorage media;
    private final RestClient client;
    private final RestClient mediaClient;

    public ConfigSyncService(JdbcTemplate jdbc, QmsProperties props, SyncStateService state, TransactionTemplate tx,
                             MediaStorage media) {
        this.jdbc = jdbc;
        this.props = props;
        this.state = state;
        this.tx = tx;
        this.media = media;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder().requestFactory(factory).build();
        var mediaFactory = new SimpleClientHttpRequestFactory();
        mediaFactory.setConnectTimeout(Duration.ofSeconds(5));
        mediaFactory.setReadTimeout(Duration.ofMinutes(5));   // videos up to 200 MB over a slow WAN
        this.mediaClient = RestClient.builder().requestFactory(mediaFactory).build();
    }

    // ------------------------------------------------------------------ central side

    /** Content from central that a site receives: its own, its region's, or national. */
    private static final String CONTENT_FOR_SITE = """
            from content_item c
            where c.origin = 'LOCAL' and c.status in ('PUBLISHED', 'ARCHIVED')
              and (c.site_id = ? or (c.site_id is null and (c.region_id is null or c.region_id = ?)))
            """;

    @Transactional
    public ConfigBundle bundle(Instant since, SitePrincipal site) {
        UUID regionId = jdbc.queryForObject("select region_id from site where id = ?", UUID.class, site.siteId());
        Timestamp from = Timestamp.from(since == null ? Instant.EPOCH : since.minus(OVERLAP));
        Instant now = Instant.now();
        return new ConfigBundle(now,
                jdbc.query("select id, code, name, active from region where updated_at >= ?",
                        (rs, i) -> new ConfigBundle.RegionRow(rs.getObject(1, java.util.UUID.class), rs.getString(2),
                                rs.getString(3), rs.getBoolean(4)), from),
                jdbc.query("select id, code, name, type, region_id, area, timezone, active from site where updated_at >= ?",
                        (rs, i) -> new ConfigBundle.SiteRow(rs.getObject(1, java.util.UUID.class), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getObject(5, java.util.UUID.class),
                                rs.getString(6), rs.getString(7), rs.getBoolean(8)), from),
                jdbc.query("""
                                select id, code, name, description, classification, client_type, service_type, multi_stage,
                                       default_prefix, default_duration_minutes, required_documents, eligibility_notes,
                                       active, sort_order, version, effective_from, effective_to
                                from service_item where updated_at >= ?
                                """,
                        (rs, i) -> new ConfigBundle.ServiceItemRow(rs.getObject(1, java.util.UUID.class),
                                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                                rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getInt(10), rs.getString(11),
                                rs.getString(12), rs.getBoolean(13), rs.getInt(14), rs.getInt(15),
                                rs.getObject(16, java.time.LocalDate.class),
                                rs.getObject(17, java.time.LocalDate.class)), from),
                jdbc.query("select id, code, name, description, rank, active from priority_category where updated_at >= ?",
                        (rs, i) -> new ConfigBundle.PriorityRow(rs.getObject(1, java.util.UUID.class), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6)), from),
                contentFor(site.siteId(), regionId, from));
    }

    private List<ConfigBundle.ContentRow> contentFor(UUID siteId, UUID regionId, Timestamp from) {
        // Media uploaded before checksums existed gets its checksum computed once, here.
        List<Object[]> missing = jdbc.query("select c.id, c.media_path " + CONTENT_FOR_SITE
                        + " and c.media_path is not null and c.media_sha256 is null",
                (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}, siteId, regionId);
        for (Object[] m : missing) {
            jdbc.update("update content_item set media_sha256 = ? where id = ?", media.sha256((String) m[1]), m[0]);
        }
        return jdbc.query("select c.id, c.title, c.type, c.body, c.media_path, c.mime_type, c.size_bytes,"
                        + " c.media_sha256, c.site_id, c.region_id, c.starts_at, c.ends_at, c.display_seconds,"
                        + " c.status, c.created_by, c.approved_by, c.approved_at "
                        + CONTENT_FOR_SITE + " and c.updated_at >= ?",
                (rs, i) -> new ConfigBundle.ContentRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        (Long) rs.getObject(7), rs.getString(8), rs.getObject(9, UUID.class),
                        rs.getObject(10, UUID.class), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                        rs.getInt(13), rs.getString(14), rs.getString(15), rs.getString(16),
                        instant(rs.getTimestamp(17))),
                siteId, regionId, from);
    }

    /** Stored media name of published content distributable to the site, for the media download endpoint. */
    @Transactional(readOnly = true)
    public String distributableMedia(UUID contentId, SitePrincipal site) {
        UUID regionId = jdbc.queryForObject("select region_id from site where id = ?", UUID.class, site.siteId());
        return jdbc.query("select c.media_path " + CONTENT_FOR_SITE
                                + " and c.id = ? and c.status = 'PUBLISHED' and c.media_path is not null",
                        (rs, i) -> rs.getString(1), site.siteId(), regionId, contentId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Media"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    // ------------------------------------------------------------------ site side

    @Scheduled(fixedDelayString = "${qms.sync.config-pull-interval-ms}", initialDelay = 5_000)
    public void pull() {
        if (!props.sync().enabled() || props.node().isCentral() || props.sync().centralUrl() == null
                || props.sync().centralUrl().isBlank()) {
            return;
        }
        try {
            String since = state.get(SyncStateService.CONFIG_WATERMARK).orElse(Instant.EPOCH.toString());
            ConfigBundle bundle = client.get()
                    .uri(props.sync().centralUrl() + "/api/v1/sync/config?since={since}", since)
                    .header(SiteKeyAuthenticationFilter.HEADER, props.sync().siteKey())
                    .retrieve().body(ConfigBundle.class);
            if (bundle != null) {
                // Media first, so a content row never points at a file the site does not have yet.
                boolean mediaComplete = downloadMedia(bundle);
                tx.executeWithoutResult(s -> apply(bundle));
                if (mediaComplete) {
                    // Keep the old watermark while any download failed, so the next pull retries it.
                    state.put(SyncStateService.CONFIG_WATERMARK, bundle.serverTime().toString());
                }
                state.put(SyncStateService.LAST_CONFIG_PULL, Instant.now().toString());
            }
        } catch (RuntimeException e) {
            log.warn("Configuration pull failed (local operation continues): {}", e.getMessage());
        }
    }

    /** Downloads media of published content not yet present locally. Returns false if any failed. */
    boolean downloadMedia(ConfigBundle b) {
        boolean complete = true;
        for (var c : b.contents()) {
            if (!"PUBLISHED".equals(c.status()) || c.mediaPath() == null || media.exists(c.mediaPath())) {
                continue;
            }
            try {
                mediaClient.get().uri(props.sync().centralUrl() + "/api/v1/sync/media/{id}", c.id())
                        .header(SiteKeyAuthenticationFilter.HEADER, props.sync().siteKey())
                        .exchange((req, res) -> {
                            if (!res.getStatusCode().is2xxSuccessful()) {
                                throw new java.io.IOException("HTTP " + res.getStatusCode().value());
                            }
                            media.storeReplica(c.mediaPath(), res.getBody(), c.sizeBytes(), c.mediaSha256());
                            return null;
                        });
            } catch (RuntimeException e) {
                complete = false;
                log.warn("Media download for content {} failed: {}", c.id(), e.getMessage());
            }
        }
        return complete;
    }

    /** Central-owned data: the central version always wins on the site. */
    void apply(ConfigBundle b) {
        for (var c : b.contents()) {
            if ("PUBLISHED".equals(c.status()) && c.mediaPath() != null && !media.exists(c.mediaPath())) {
                continue;   // media not downloaded yet; applied on a later pull
            }
            jdbc.update("""
                    insert into content_item (id, title, type, body, media_path, mime_type, size_bytes, media_sha256,
                        site_id, region_id, starts_at, ends_at, display_seconds, status, created_by, approved_by,
                        approved_at, origin, created_at, updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CENTRAL',now(),now())
                    on conflict (id) do update set title = excluded.title, body = excluded.body,
                        media_path = excluded.media_path, mime_type = excluded.mime_type,
                        size_bytes = excluded.size_bytes, media_sha256 = excluded.media_sha256,
                        starts_at = excluded.starts_at, ends_at = excluded.ends_at,
                        display_seconds = excluded.display_seconds, status = excluded.status,
                        approved_by = excluded.approved_by, approved_at = excluded.approved_at, updated_at = now()
                    where content_item.origin = 'CENTRAL'
                    """, c.id(), c.title(), c.type(), c.body(), c.mediaPath(), c.mimeType(), c.sizeBytes(),
                    c.mediaSha256(), c.siteId(), c.regionId(), ts(c.startsAt()), ts(c.endsAt()), c.displaySeconds(),
                    c.status(), c.createdBy(), c.approvedBy(), ts(c.approvedAt()));
        }
        for (var r : b.regions()) {
            jdbc.update("""
                    insert into region (id, code, name, active, created_at, updated_at) values (?,?,?,?,now(),now())
                    on conflict (id) do update set code = excluded.code, name = excluded.name, active = excluded.active,
                        updated_at = now()
                    """, r.id(), r.code(), r.name(), r.active());
        }
        for (var s : b.sites()) {
            jdbc.update("""
                    insert into site (id, code, name, type, region_id, area, timezone, active, created_at, updated_at)
                    values (?,?,?,?,?,?,?,?,now(),now())
                    on conflict (id) do update set code = excluded.code, name = excluded.name, type = excluded.type,
                        region_id = excluded.region_id, area = excluded.area, timezone = excluded.timezone,
                        active = excluded.active, updated_at = now()
                    """, s.id(), s.code(), s.name(), s.type(), s.regionId(), s.area(), s.timezone(), s.active());
        }
        for (var i : b.serviceItems()) {
            jdbc.update("""
                    insert into service_item (id, code, name, description, classification, client_type, service_type,
                        multi_stage, default_prefix, default_duration_minutes, required_documents, eligibility_notes,
                        active, sort_order, version, effective_from, effective_to, created_at, updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
                    on conflict (id) do update set code = excluded.code, name = excluded.name,
                        description = excluded.description, classification = excluded.classification,
                        client_type = excluded.client_type, service_type = excluded.service_type,
                        multi_stage = excluded.multi_stage, default_prefix = excluded.default_prefix,
                        default_duration_minutes = excluded.default_duration_minutes,
                        required_documents = excluded.required_documents, eligibility_notes = excluded.eligibility_notes,
                        active = excluded.active, sort_order = excluded.sort_order, version = excluded.version,
                        effective_from = excluded.effective_from, effective_to = excluded.effective_to,
                        updated_at = now()
                    """, i.id(), i.code(), i.name(), i.description(), i.classification(), i.clientType(),
                    i.serviceType(), i.multiStage(), i.defaultPrefix(), i.defaultDurationMinutes(),
                    i.requiredDocuments(), i.eligibilityNotes(), i.active(), i.sortOrder(), i.version(),
                    i.effectiveFrom(), i.effectiveTo());
        }
        for (var p : b.priorityCategories()) {
            jdbc.update("""
                    insert into priority_category (id, code, name, description, rank, active, created_at, updated_at)
                    values (?,?,?,?,?,?,now(),now())
                    on conflict (id) do update set code = excluded.code, name = excluded.name,
                        description = excluded.description, rank = excluded.rank, active = excluded.active,
                        updated_at = now()
                    """, p.id(), p.code(), p.name(), p.description(), p.rank(), p.active());
        }
    }
}

package ph.gov.philhealth.qms.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ph.gov.philhealth.qms.audit.AuditLog;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.security.SitePrincipal;

/**
 * Central-node receiver. Each entry is verified (site identity, SHA-256 integrity), de-duplicated by
 * idempotency key, and applied in its own transaction. Conflict policy: tickets and their events are
 * owned by the issuing site; an incoming ticket snapshot replaces the central copy only if its
 * optimistic-lock version is not older (last-writer-by-version wins, never a stale overwrite).
 * Rejected entries are not recorded, so the site retries them later.
 */
@Service
public class SyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(SyncIngestService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final QmsProperties props;
    private final ReconciliationService reconciliation;

    public SyncIngestService(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate tx, QmsProperties props,
                             ReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = tx;
        this.props = props;
        this.reconciliation = reconciliation;
    }

    public SyncEnvelope.Result ingest(SitePrincipal site, List<SyncEnvelope.Entry> entries) {
        if (!props.node().isCentral()) {
            throw ApiException.forbidden("This node does not accept synchronization uploads");
        }
        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<SyncEnvelope.Rejected> rejected = new ArrayList<>();
        for (SyncEnvelope.Entry e : entries) {
            try {
                if (!site.siteCode().equals(e.siteCode())) {
                    rejected.add(new SyncEnvelope.Rejected(e.idempotencyKey(), "Site code does not match credentials"));
                    continue;
                }
                if (!Hashing.sha256Hex(e.payload()).equals(e.payloadSha256())) {
                    rejected.add(new SyncEnvelope.Rejected(e.idempotencyKey(), "Payload integrity check failed"));
                    continue;
                }
                Boolean applied = tx.execute(s -> {
                    int inserted = jdbc.update("""
                            insert into sync_inbox (idempotency_key, site_code, event_type, received_at, status)
                            values (?, ?, ?, now(), 'APPLIED') on conflict do nothing
                            """, e.idempotencyKey(), e.siteCode(), e.eventType());
                    if (inserted == 0) {
                        return false;
                    }
                    apply(site, e);
                    return true;
                });
                (Boolean.TRUE.equals(applied) ? accepted : duplicates).add(e.idempotencyKey());
            } catch (RuntimeException ex) {
                log.warn("Rejected sync entry {} from {}: {}", e.idempotencyKey(), site.siteCode(), ex.getMessage());
                rejected.add(new SyncEnvelope.Rejected(e.idempotencyKey(), rootMessage(ex)));
            }
        }
        return new SyncEnvelope.Result(accepted, duplicates, rejected);
    }

    private void apply(SitePrincipal site, SyncEnvelope.Entry e) {
        try {
            switch (e.eventType()) {
                case "TICKET_EVENT" -> applyTicket(site, mapper.readValue(e.payload(), Snapshots.TicketChange.class));
                case "SITE_SERVICE_UPSERT" -> applySiteService(site,
                        mapper.readValue(e.payload(), Snapshots.SiteServiceSnapshot.class));
                case "COUNTER_UPSERT" -> applyCounter(site, mapper.readValue(e.payload(), Snapshots.CounterSnapshot.class));
                case "DEVICE_UPSERT" -> applyDevice(site, mapper.readValue(e.payload(), Snapshots.DeviceSnapshot.class));
                case "FEEDBACK_SUBMITTED" -> applyFeedback(site,
                        mapper.readValue(e.payload(), Snapshots.FeedbackSnapshot.class));
                case "AUDIT_RECORDED" -> applyAudit(mapper.readValue(e.payload(), AuditLog.class));
                case "DAILY_SUMMARY" -> {
                    var summary = mapper.readValue(e.payload(), ReconciliationService.DailySummary.class);
                    requireSameSite(site, summary.siteId());
                    reconciliation.apply(summary);
                }
                default -> throw new IllegalArgumentException("Unknown event type " + e.eventType());
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Malformed payload", ex);
        }
    }

    private static void requireSameSite(SitePrincipal site, java.util.UUID siteId) {
        if (!site.siteId().equals(siteId)) {
            throw new IllegalArgumentException("Record belongs to another site");
        }
    }

    private void applySiteService(SitePrincipal site, Snapshots.SiteServiceSnapshot s) {
        requireSameSite(site, s.siteId());
        jdbc.update("""
                insert into site_service (id, site_id, service_item_id, prefix, active, sort_order,
                    estimated_duration_minutes, open_time, close_time, next_site_service_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (id) do update set prefix = excluded.prefix, active = excluded.active,
                    sort_order = excluded.sort_order, estimated_duration_minutes = excluded.estimated_duration_minutes,
                    open_time = excluded.open_time, close_time = excluded.close_time,
                    next_site_service_id = excluded.next_site_service_id, updated_at = now()
                """, s.id(), s.siteId(), s.serviceItemId(), s.prefix(), s.active(), s.sortOrder(),
                s.estimatedDurationMinutes(), s.openTime(), s.closeTime(), s.nextSiteServiceId());
    }

    private void applyCounter(SitePrincipal site, Snapshots.CounterSnapshot c) {
        requireSameSite(site, c.siteId());
        jdbc.update("""
                insert into service_counter (id, site_id, code, name, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                on conflict (id) do update set code = excluded.code, name = excluded.name,
                    active = excluded.active, updated_at = now()
                """, c.id(), c.siteId(), c.code(), c.name(), c.active());
        jdbc.update("delete from counter_service where counter_id = ?", c.id());
        for (var sid : c.siteServiceIds()) {
            jdbc.update("insert into counter_service (counter_id, site_service_id) values (?, ?)", c.id(), sid);
        }
    }

    private void applyDevice(SitePrincipal site, Snapshots.DeviceSnapshot d) {
        requireSameSite(site, d.siteId());
        // The central copy is descriptive only: it gets an unusable random key hash.
        jdbc.update("""
                insert into device (id, site_id, code, name, type, api_key_hash, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (id) do update set code = excluded.code, name = excluded.name,
                    active = excluded.active, updated_at = now()
                """, d.id(), d.siteId(), d.code(), d.name(), d.type(),
                Hashing.sha256Hex("replica:" + Hashing.randomToken(24)), d.active());
    }

    private void applyTicket(SitePrincipal site, Snapshots.TicketChange change) {
        Snapshots.TicketSnapshot t = change.ticket();
        requireSameSite(site, t.siteId());
        jdbc.update("""
                insert into ticket (id, site_id, site_service_id, original_site_service_id, business_date, prefix,
                    seq_no, display_number, priority_category_id, priority_rank, channel, device_id, status, stage_no,
                    counter_id, agent_user_id, agent_name, issued_at, queued_at, first_called_at, last_called_at,
                    completed_at, status_changed_at, recall_count, transfer_count, wait_seconds, service_seconds,
                    hold_seconds, version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (id) do update set site_service_id = excluded.site_service_id, status = excluded.status,
                    stage_no = excluded.stage_no, counter_id = excluded.counter_id,
                    agent_user_id = excluded.agent_user_id, agent_name = excluded.agent_name,
                    queued_at = excluded.queued_at, first_called_at = excluded.first_called_at,
                    last_called_at = excluded.last_called_at, completed_at = excluded.completed_at,
                    status_changed_at = excluded.status_changed_at, recall_count = excluded.recall_count,
                    transfer_count = excluded.transfer_count, wait_seconds = excluded.wait_seconds,
                    service_seconds = excluded.service_seconds, hold_seconds = excluded.hold_seconds,
                    version = excluded.version
                where ticket.version <= excluded.version
                """, t.id(), t.siteId(), t.siteServiceId(), t.originalSiteServiceId(), t.businessDate(), t.prefix(),
                t.seqNo(), t.displayNumber(), t.priorityCategoryId(), t.priorityRank(), t.channel(), t.deviceId(),
                t.status(), t.stageNo(), t.counterId(), t.agentUserId(), t.agentName(), ts(t.issuedAt()),
                ts(t.queuedAt()), ts(t.firstCalledAt()), ts(t.lastCalledAt()), ts(t.completedAt()),
                ts(t.statusChangedAt()), t.recallCount(), t.transferCount(), t.waitSeconds(), t.serviceSeconds(),
                t.holdSeconds(), t.version());
        Snapshots.TicketEventSnapshot ev = change.event();
        jdbc.update("""
                insert into ticket_event (id, ticket_id, site_id, event_type, from_status, to_status, at,
                    actor_user_id, actor_name, counter_id, site_service_id, reason)
                values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (id) do nothing
                """, ev.id(), ev.ticketId(), ev.siteId(), ev.eventType(), ev.fromStatus(), ev.toStatus(), ts(ev.at()),
                ev.actorUserId(), ev.actorName(), ev.counterId(), ev.siteServiceId(), ev.reason());
    }

    private void applyFeedback(SitePrincipal site, Snapshots.FeedbackSnapshot f) {
        requireSameSite(site, f.siteId());
        jdbc.update("""
                insert into feedback (id, ticket_id, site_id, rating, comment, submitted_at)
                values (?,?,?,?,?,?) on conflict (id) do nothing
                """, f.id(), f.ticketId(), f.siteId(), f.rating(), f.comment(), ts(f.submittedAt()));
    }

    private void applyAudit(AuditLog a) {
        jdbc.update("""
                insert into audit_log (id, at, actor_user_id, actor_username, action, entity_type, entity_id, site_id,
                    details, ip_address)
                values (?,?,?,?,?,?,?,?,?,?) on conflict (id) do nothing
                """, a.getId(), ts(a.getAt()), a.getActorUserId(), a.getActorUsername(), a.getAction(),
                a.getEntityType(), a.getEntityId(), a.getSiteId(), a.getDetails(), a.getIpAddress());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) {
            r = r.getCause();
        }
        String m = r.getMessage();
        return m == null ? r.getClass().getSimpleName() : (m.length() > 500 ? m.substring(0, 500) : m);
    }
}

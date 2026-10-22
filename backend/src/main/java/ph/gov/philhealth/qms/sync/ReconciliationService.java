package ph.gov.philhealth.qms.sync;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.TimeService;

/**
 * Detects records lost or stuck between a site and central. Each site periodically reports its own
 * per-day counts (through the outbox, so the report arrives after the records it counts); central
 * compares them with the rows it actually holds.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final int DAYS_REPORTED = 7;

    public enum Status { MATCH, IN_TRANSIT, MISMATCH }

    public record DailySummary(UUID siteId, LocalDate businessDate, long tickets, long events, long feedback,
                               long outboxPending, Instant generatedAt) {
    }

    public record Row(UUID siteId, String siteCode, String siteName, LocalDate businessDate, long siteTickets,
                      long centralTickets, long siteEvents, long centralEvents, long siteFeedback,
                      long centralFeedback, long siteOutboxPending, Instant reportedAt, Status status) {
    }

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final SiteRepository sites;
    private final QmsProperties props;
    private final TimeService time;

    public ReconciliationService(JdbcTemplate jdbc, OutboxService outbox, SiteRepository sites, QmsProperties props,
                                 TimeService time) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.sites = sites;
        this.props = props;
        this.time = time;
    }

    // ------------------------------------------------------------------ site side

    @Scheduled(cron = "${qms.sync.reconcile-cron:0 30 * * * *}")
    public void scheduledReport() {
        if (!outbox.isActive()) {
            return;
        }
        try {
            reportRecentDays();
        } catch (RuntimeException e) {
            log.warn("Reconciliation summary failed: {}", e.getMessage());
        }
    }

    /** Queues summaries for today and the previous days of this node's site. */
    @Transactional
    public int reportRecentDays() {
        Site site = sites.findByCode(props.node().siteCode())
                .orElseThrow(() -> new IllegalStateException("Local site " + props.node().siteCode() + " not found"));
        LocalDate today = time.businessDate(site.getTimezone());
        long pending = jdbc.queryForObject("select count(*) from sync_outbox where status <> 'SENT'", Long.class);
        for (int i = 0; i < DAYS_REPORTED; i++) {
            DailySummary s = summarize(site.getId(), today.minusDays(i), pending);
            outbox.enqueue("DAILY_SUMMARY", "Site", site.getId() + ":" + s.businessDate(), s);
        }
        return DAYS_REPORTED;
    }

    DailySummary summarize(UUID siteId, LocalDate date, long pending) {
        return jdbc.queryForObject("""
                select (select count(*) from ticket t where t.site_id = ? and t.business_date = ?),
                       (select count(*) from ticket_event e join ticket t on t.id = e.ticket_id
                         where t.site_id = ? and t.business_date = ?),
                       (select count(*) from feedback f join ticket t on t.id = f.ticket_id
                         where t.site_id = ? and t.business_date = ?)
                """, (rs, i) -> new DailySummary(siteId, date, rs.getLong(1), rs.getLong(2), rs.getLong(3), pending,
                time.now()), siteId, date, siteId, date, siteId, date);
    }

    // ------------------------------------------------------------------ central side

    /** Applies a site's summary on the central node (called by {@link SyncIngestService}). */
    void apply(DailySummary s) {
        jdbc.update("""
                insert into sync_reconciliation (site_id, business_date, site_ticket_count, site_event_count,
                    site_feedback_count, site_outbox_pending, reported_at)
                values (?,?,?,?,?,?,?)
                on conflict (site_id, business_date) do update set site_ticket_count = excluded.site_ticket_count,
                    site_event_count = excluded.site_event_count, site_feedback_count = excluded.site_feedback_count,
                    site_outbox_pending = excluded.site_outbox_pending, reported_at = excluded.reported_at
                where sync_reconciliation.reported_at <= excluded.reported_at
                """, s.siteId(), Date.valueOf(s.businessDate()), s.tickets(), s.events(), s.feedback(),
                s.outboxPending(), Timestamp.from(s.generatedAt()));
    }

    @Transactional(readOnly = true)
    public List<Row> report(LocalDate from, LocalDate to, boolean onlyProblems) {
        if (!props.node().isCentral()) {
            throw ApiException.forbidden("Reconciliation is available on the central node");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("A valid from/to date range is required");
        }
        List<Row> rows = jdbc.query("""
                select r.site_id, s.code, s.name, r.business_date,
                       r.site_ticket_count, r.site_event_count, r.site_feedback_count, r.site_outbox_pending,
                       r.reported_at,
                       (select count(*) from ticket t where t.site_id = r.site_id
                          and t.business_date = r.business_date) as c_tickets,
                       (select count(*) from ticket_event e join ticket t on t.id = e.ticket_id
                         where t.site_id = r.site_id and t.business_date = r.business_date) as c_events,
                       (select count(*) from feedback f join ticket t on t.id = f.ticket_id
                         where t.site_id = r.site_id and t.business_date = r.business_date) as c_feedback
                from sync_reconciliation r join site s on s.id = r.site_id
                where r.business_date between ? and ?
                order by r.business_date desc, s.code
                """, (rs, i) -> {
            long st = rs.getLong("site_ticket_count"), ct = rs.getLong("c_tickets");
            long se = rs.getLong("site_event_count"), ce = rs.getLong("c_events");
            long sf = rs.getLong("site_feedback_count"), cf = rs.getLong("c_feedback");
            long pending = rs.getLong("site_outbox_pending");
            return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, LocalDate.class), st, ct, se, ce, sf, cf, pending,
                    rs.getTimestamp("reported_at").toInstant(), status(st, ct, se, ce, sf, cf, pending));
        }, Date.valueOf(from), Date.valueOf(to));
        return onlyProblems ? rows.stream().filter(r -> r.status() != Status.MATCH).toList() : rows;
    }

    /**
     * MATCH when central holds exactly what the site reported. Fewer rows while the site still had
     * unsent entries is IN_TRANSIT; anything else (fewer with an empty backlog, or more) is a MISMATCH.
     */
    static Status status(long st, long ct, long se, long ce, long sf, long cf, long pending) {
        if (st == ct && se == ce && sf == cf) {
            return Status.MATCH;
        }
        boolean centralBehind = ct <= st && ce <= se && cf <= sf;
        return centralBehind && pending > 0 ? Status.IN_TRANSIT : Status.MISMATCH;
    }
}

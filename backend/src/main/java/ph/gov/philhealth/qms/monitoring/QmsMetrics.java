package ph.gov.philhealth.qms.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operational metrics exported at /actuator/prometheus (see deploy/monitoring for scrape config and
 * alert rules). Event counters are incremented by the services; state gauges are refreshed from the
 * database every 30 seconds so a scrape never runs queries. Tags are bounded (site codes, fixed
 * enums) to keep cardinality low.
 */
@Component
public class QmsMetrics {

    private static final Logger log = LoggerFactory.getLogger(QmsMetrics.class);

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final MultiGauge waitingBySite;
    private final MultiGauge sessionsByStatus;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxFailed = new AtomicLong();
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong();
    private final DistributionSummary queueTime;

    public QmsMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.waitingBySite = MultiGauge.builder("qms.queue.waiting")
                .description("Tickets currently waiting, per site (today's business date)").baseUnit("tickets")
                .register(registry);
        this.sessionsByStatus = MultiGauge.builder("qms.agent.sessions")
                .description("Open agent counter sessions by status").register(registry);
        Gauge.builder("qms.sync.outbox.entries", outboxPending, AtomicLong::get).tag("status", "pending")
                .description("Outbox entries not yet delivered to central").register(registry);
        Gauge.builder("qms.sync.outbox.entries", outboxFailed, AtomicLong::get).tag("status", "failed")
                .register(registry);
        Gauge.builder("qms.sync.outbox.oldest.pending.age", outboxOldestPendingSeconds, AtomicLong::get)
                .baseUnit("seconds").description("Age of the oldest undelivered outbox entry").register(registry);
        this.queueTime = DistributionSummary.builder("qms.ticket.queue.time")
                .description("Time from ticket issue to first call").baseUnit("seconds")
                .publishPercentileHistogram()
                .serviceLevelObjectives(300, 900, 1800, 3600)
                .register(registry);
    }

    /** Runs the recording after the current transaction commits (immediately if there is none). */
    public static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    public void ticketIssued(String siteCode, String channel) {
        Counter.builder("qms.tickets.issued").description("Tickets issued at kiosks")
                .tags("site", siteCode, "channel", channel).register(registry).increment();
    }

    public void ticketAction(String action) {
        Counter.builder("qms.ticket.actions").description("Ticket status changes by action")
                .tag("action", action).register(registry).increment();
    }

    public void firstCalled(long queueSeconds) {
        queueTime.record(Math.max(0, queueSeconds));
    }

    public void reportRun(String status) {
        Counter.builder("qms.report.runs").description("Scheduled report generations by result")
                .tag("status", status).register(registry).increment();
    }

    public void syncPush(String result, int entries) {
        Counter.builder("qms.sync.push.entries").description("Outbox entries pushed to central by result")
                .tag("result", result).register(registry).increment(entries);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void refreshGauges() {
        try {
            List<MultiGauge.Row<?>> waiting = jdbc.query("""
                    select s.code, count(t.id) from site s
                    left join ticket t on t.site_id = s.id and t.status = 'WAITING'
                         and t.business_date = (now() at time zone s.timezone)::date
                    where s.active group by s.code
                    """, (rs, i) -> MultiGauge.Row.of(Tags.of("site", rs.getString(1)), rs.getLong(2)));
            waitingBySite.register(waiting, true);
            List<MultiGauge.Row<?>> sessions = jdbc.query("""
                    select status, count(*) from agent_session where status <> 'ENDED' group by status
                    """, (rs, i) -> MultiGauge.Row.of(Tags.of("status", rs.getString(1)), rs.getLong(2)));
            sessionsByStatus.register(sessions, true);
            jdbc.query("""
                    select count(*) filter (where status = 'PENDING'), count(*) filter (where status = 'FAILED'),
                           coalesce(extract(epoch from now() - min(created_at) filter (where status <> 'SENT')), 0)
                    from sync_outbox
                    """, rs -> {
                outboxPending.set(rs.getLong(1));
                outboxFailed.set(rs.getLong(2));
                outboxOldestPendingSeconds.set((long) rs.getDouble(3));
            });
        } catch (RuntimeException e) {
            log.warn("Metric refresh failed: {}", e.getMessage());
        }
    }
}

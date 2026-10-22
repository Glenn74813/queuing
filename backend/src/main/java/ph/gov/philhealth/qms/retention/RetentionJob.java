package ph.gov.philhealth.qms.retention;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.config.QmsProperties.Retention;

/**
 * Deletes data older than the configured retention (OI-13). Tickets and audit entries go through the
 * owner-defined purge functions of V11, since the application role cannot delete that history itself.
 * A site node never purges tickets whose changes have not yet reached central. Each run that deletes
 * anything leaves an audit entry with the counts.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    /** Ticket retention below this is refused: it would remove data the current reports still show. */
    static final int MIN_TICKET_DAYS = 31;
    private static final int MAX_BATCHES = 200;

    public record Result(int tickets, int auditEntries, int outboxRows, int refreshTokens, String skipped) {
        boolean deletedAnything() {
            return tickets + auditEntries + outboxRows + refreshTokens > 0;
        }
    }

    private final JdbcTemplate jdbc;
    private final QmsProperties props;
    private final TimeService time;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public RetentionJob(JdbcTemplate jdbc, QmsProperties props, TimeService time, AuditService audit,
                        PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.props = props;
        this.time = time;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "${qms.retention.cron}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Retention run failed", e);
        }
    }

    public Result run() {
        Retention r = props.retention();
        Instant now = time.now();
        int batch = Math.max(100, r.batchSize());
        String skipped = null;

        int tickets = 0;
        if (r.ticketDays() > 0) {
            if (r.ticketDays() < MIN_TICKET_DAYS) {
                skipped = "ticket retention below " + MIN_TICKET_DAYS + " days is not applied";
            } else {
                ZoneId zone = ZoneId.of(props.node().defaultTimezone());
                LocalDate cutoff = LocalDate.ofInstant(now, zone).minusDays(r.ticketDays());
                if (unsentChangesBefore(cutoff.atStartOfDay(zone).toInstant())) {
                    skipped = "tickets not purged: changes older than the cutoff are still waiting to sync to central";
                } else {
                    tickets = repeat(() -> jdbc.queryForObject("select qms_purge_tickets(?, ?)", Integer.class,
                            cutoff, batch), batch);
                }
            }
        }
        int auditEntries = r.auditDays() <= 0 ? 0 : repeat(() -> jdbc.queryForObject(
                "select qms_purge_audit(?, ?)", Integer.class, ts(now.minus(Duration.ofDays(r.auditDays()))), batch), batch);
        int outbox = r.sentOutboxDays() <= 0 ? 0 : jdbc.update(
                "delete from sync_outbox where status = 'SENT' and sent_at < ?",
                ts(now.minus(Duration.ofDays(r.sentOutboxDays()))));
        int tokens = r.expiredTokenDays() <= 0 ? 0 : jdbc.update(
                "delete from refresh_token where expires_at < ?", ts(now.minus(Duration.ofDays(r.expiredTokenDays()))));

        Result result = new Result(tickets, auditEntries, outbox, tokens, skipped);
        if (result.deletedAnything() || skipped != null) {
            log.info("Retention run: {}", result);
            String details = "tickets=%d audit=%d outbox=%d refreshTokens=%d%s".formatted(tickets, auditEntries, outbox,
                    tokens, skipped != null ? "; " + skipped : "");
            tx.executeWithoutResult(s -> audit.record("RETENTION_PURGE", "System", null, null, details));
        }
        return result;
    }

    private boolean unsentChangesBefore(Instant cutoff) {
        Integer n = jdbc.queryForObject("select count(*) from sync_outbox where status <> 'SENT' and created_at < ?",
                Integer.class, ts(cutoff));
        return n != null && n > 0;
    }

    // Each purge batch commits on its own (no surrounding transaction), so a long run holds no long locks.

    /** Runs a batched purge until a batch comes back short. */
    private static int repeat(java.util.function.Supplier<Integer> purge, int batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer n = purge.get();
            total += n == null ? 0 : n;
            if (n == null || n < batch) {
                break;
            }
        }
        return total;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}

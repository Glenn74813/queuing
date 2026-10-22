package ph.gov.philhealth.qms.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Old tickets (with events and feedback) and audit entries are purged; recent and unsynced data stay. */
@IntegrationTest
@TestPropertySource(properties = {"qms.retention.ticket-days=400", "qms.retention.audit-days=400"})
class RetentionIT {

    @Autowired
    RetentionJob job;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestData data;

    private UUID ticket(TestData.SiteFixture site, LocalDate date, String status) {
        UUID id = Ids.newId();
        Timestamp at = Timestamp.valueOf(date.atTime(9, 0));
        int seq = jdbc.queryForObject("select coalesce(max(seq_no), 0) + 1 from ticket where site_id = ?", Integer.class,
                site.siteId());
        jdbc.update("""
                insert into ticket (id, site_id, site_service_id, original_site_service_id, business_date, prefix, seq_no,
                    display_number, channel, status, issued_at, queued_at, status_changed_at)
                values (?,?,?,?,?, 'A', ?, ?, 'PRINTED', ?, ?, ?, ?)""",
                id, site.siteId(), site.serviceA(), site.serviceA(), date, seq, "A-" + seq, status, at, at, at);
        jdbc.update("insert into ticket_event (id, ticket_id, site_id, event_type, to_status, at) values (?,?,?, 'ISSUED', ?, ?)",
                Ids.newId(), id, site.siteId(), status, at);
        return id;
    }

    @Test
    void purgesOnlyWhatIsOlderThanTheRetention() {
        TestData.SiteFixture site = data.site();
        LocalDate old = LocalDate.now().minusDays(500);
        UUID oldDone = ticket(site, old, "COMPLETED");
        jdbc.update("insert into feedback (id, ticket_id, site_id, rating, submitted_at) values (?,?,?,5, now())",
                Ids.newId(), oldDone, site.siteId());
        UUID recent = ticket(site, LocalDate.now().minusDays(10), "COMPLETED");
        UUID oldAudit = Ids.newId();
        jdbc.update("insert into audit_log (id, at, action) values (?, ?, 'TEST_OLD')", oldAudit,
                Timestamp.from(Instant.now().minus(500, ChronoUnit.DAYS)));

        // An unsent sync change from before the cutoff blocks the ticket purge (site must sync first).
        UUID pending = Ids.newId();
        jdbc.update("""
                insert into sync_outbox (id, idempotency_key, event_type, aggregate_type, aggregate_id, site_code, payload,
                    payload_sha256, status, next_attempt_at, created_at)
                values (?, ?, 'TICKET_EVENT', 'Ticket', 'x', 'S', '{}', 'x', 'PENDING', now(), ?)""",
                pending, "test-" + pending, Timestamp.from(Instant.now().minus(450, ChronoUnit.DAYS)));
        RetentionJob.Result blocked = job.run();
        assertThat(blocked.tickets()).isZero();
        assertThat(blocked.skipped()).contains("sync");
        assertThat(blocked.auditEntries()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from audit_log where id = ?", oldAudit)).isZero();

        jdbc.update("update sync_outbox set status = 'SENT', sent_at = now() where id = ?", pending);
        RetentionJob.Result r = job.run();
        assertThat(r.tickets()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from ticket where id = ?", oldDone)).isZero();
        assertThat(count("select count(*) from ticket_event where ticket_id = ?", oldDone)).isZero();
        assertThat(count("select count(*) from feedback where ticket_id = ?", oldDone)).isZero();
        assertThat(count("select count(*) from ticket where id = ?", recent)).isOne();
        assertThat(count("select count(*) from audit_log where action = 'RETENTION_PURGE'", null)).isPositive();
    }

    private int count(String sql, UUID arg) {
        return arg == null ? jdbc.queryForObject(sql, Integer.class) : jdbc.queryForObject(sql, Integer.class, arg);
    }
}

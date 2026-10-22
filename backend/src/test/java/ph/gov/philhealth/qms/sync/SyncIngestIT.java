package ph.gov.philhealth.qms.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.security.SitePrincipal;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Central-node ingest: idempotency, integrity verification, site ownership and version-based conflicts. */
@IntegrationTest
@TestPropertySource(properties = "qms.node.role=CENTRAL")
class SyncIngestIT {

    @Autowired
    SyncIngestService ingest;
    @Autowired
    TestData data;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationService reconciliation;

    private SyncEnvelope.Entry summaryEntry(String siteCode, ReconciliationService.DailySummary s) throws Exception {
        String payload = mapper.writeValueAsString(s);
        return new SyncEnvelope.Entry(siteCode + ":" + Ids.newId(), siteCode, "DAILY_SUMMARY", "Site",
                s.siteId().toString(), payload, Hashing.sha256Hex(payload), Instant.now());
    }

    @Test
    void reconciliationComparesSiteCountsWithCentral() throws Exception {
        TestData.SiteFixture site = data.site();
        SitePrincipal principal = new SitePrincipal(site.siteId(), site.siteCode());
        LocalDate today = LocalDate.now();
        // Central received one ticket with one event for today.
        assertThat(ingest.ingest(principal, List.of(entry(site.siteCode(), change(site, Ids.newId(), "WAITING", 0),
                false))).accepted()).hasSize(1);

        // The site says it has 1 ticket / 1 event today, but 3 tickets yesterday with nothing pending.
        var todayOk = new ReconciliationService.DailySummary(site.siteId(), today, 1, 1, 0, 0, Instant.now());
        var yesterdayLost = new ReconciliationService.DailySummary(site.siteId(), today.minusDays(1), 3, 6, 0, 0,
                Instant.now());
        assertThat(ingest.ingest(principal, List.of(summaryEntry(site.siteCode(), todayOk),
                summaryEntry(site.siteCode(), yesterdayLost))).accepted()).hasSize(2);

        var rows = reconciliation.report(today.minusDays(1), today, false).stream()
                .filter(r -> r.siteId().equals(site.siteId())).toList();
        assertThat(rows).extracting(ReconciliationService.Row::status)
                .containsExactly(ReconciliationService.Status.MATCH, ReconciliationService.Status.MISMATCH);
        assertThat(reconciliation.report(today.minusDays(1), today, true))
                .noneMatch(r -> r.siteId().equals(site.siteId()) && r.businessDate().equals(today));

        // A site may not report counts for another site.
        TestData.SiteFixture other = data.site();
        var forged = new ReconciliationService.DailySummary(other.siteId(), today, 0, 0, 0, 0, Instant.now());
        assertThat(ingest.ingest(principal, List.of(summaryEntry(site.siteCode(), forged))).rejected()).hasSize(1);
    }

    private SyncEnvelope.Entry entry(String siteCode, Snapshots.TicketChange change, boolean tamper) throws Exception {
        String payload = mapper.writeValueAsString(change);
        String hash = Hashing.sha256Hex(payload);
        return new SyncEnvelope.Entry(siteCode + ":" + Ids.newId(), siteCode, "TICKET_EVENT", "Ticket",
                change.ticket().id().toString(), tamper ? payload.replace("A-001", "A-999") : payload, hash,
                Instant.now());
    }

    private static Snapshots.TicketChange change(TestData.SiteFixture s, UUID ticketId, String status, long version) {
        Instant now = Instant.now();
        var t = new Snapshots.TicketSnapshot(ticketId, s.siteId(), s.serviceA(), s.serviceA(), LocalDate.now(), "A", 1,
                "A-001", null, 100, "PRINTED", null, status, 1, null, null, null, now, now, null, null, null, now, 0, 0,
                0, 0, 0, version);
        var e = new Snapshots.TicketEventSnapshot(Ids.newId(), ticketId, s.siteId(), "ISSUE", null, status, now, null,
                "Device K", null, s.serviceA(), null);
        return new Snapshots.TicketChange(t, e);
    }

    @Test
    void appliesOnceAndRejectsBadEntries() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture other = data.site();
        SitePrincipal principal = new SitePrincipal(site.siteId(), site.siteCode());
        UUID ticketId = Ids.newId();

        var first = entry(site.siteCode(), change(site, ticketId, "WAITING", 0), false);
        var result = ingest.ingest(principal, List.of(first));
        assertThat(result.accepted()).containsExactly(first.idempotencyKey());

        // Same idempotency key again → duplicate, nothing re-applied.
        assertThat(ingest.ingest(principal, List.of(first)).duplicates()).containsExactly(first.idempotencyKey());

        // Newer version updates; an older (late-arriving) version does not overwrite it.
        ingest.ingest(principal, List.of(entry(site.siteCode(), change(site, ticketId, "COMPLETED", 3), false)));
        ingest.ingest(principal, List.of(entry(site.siteCode(), change(site, ticketId, "CALLED", 2), false)));
        assertThat(jdbc.queryForObject("select status from ticket where id = ?", String.class, ticketId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from ticket_event where ticket_id = ?", Integer.class, ticketId))
                .isEqualTo(3);

        // Tampered payload, and data belonging to another site, are rejected (and not marked received).
        var tampered = entry(site.siteCode(), change(site, Ids.newId(), "WAITING", 0), true);
        var foreign = entry(site.siteCode(), change(other, Ids.newId(), "WAITING", 0), false);
        var r = ingest.ingest(principal, List.of(tampered, foreign));
        assertThat(r.rejected()).extracting(SyncEnvelope.Rejected::key)
                .containsExactlyInAnyOrder(tampered.idempotencyKey(), foreign.idempotencyKey());
        assertThat(jdbc.queryForObject("select count(*) from sync_inbox where idempotency_key in (?, ?)",
                Integer.class, tampered.idempotencyKey(), foreign.idempotencyKey())).isZero();
    }
}

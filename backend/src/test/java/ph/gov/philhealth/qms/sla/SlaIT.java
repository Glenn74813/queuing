package ph.gov.philhealth.qms.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Regional SLA administration and agent performance measured against it. */
@IntegrationTest
class SlaIT {

    private static final ZoneId MNL = ZoneId.of("Asia/Manila");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate day = LocalDate.now(MNL).minusDays(1);

    private Timestamp at(String hhmm) {
        return Timestamp.from(day.atTime(LocalTime.parse(hhmm)).atZone(MNL).toInstant());
    }

    private UUID userId(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", UUID.class, username);
    }

    private UUID ticket(TestData.SiteFixture site, UUID service, int seq, String issued, String status) {
        UUID id = Ids.newId();
        jdbc.update("""
                insert into ticket (id, site_id, site_service_id, original_site_service_id, business_date, prefix, seq_no,
                    display_number, channel, status, issued_at, queued_at, status_changed_at)
                values (?, ?, ?, ?, ?, 'A', ?, ?, 'PRINTED', ?, ?, ?, ?)
                """, id, site.siteId(), service, service, day, seq, "A-%03d".formatted(seq), status, at(issued),
                at(issued), at(issued));
        return id;
    }

    /** Appends an event; the actor is the agent (null for the kiosk issue). */
    private void event(UUID ticketId, TestData.SiteFixture site, UUID service, String type, String time, UUID actor,
                       String actorName) {
        jdbc.update("""
                insert into ticket_event (id, ticket_id, site_id, event_type, to_status, at, actor_user_id, actor_name,
                    site_service_id)
                values (?, ?, ?, ?, 'WAITING', ?, ?, ?, ?)
                """, Ids.newId(), ticketId, site.siteId(), type, at(time), actor, actorName, service);
    }

    private JsonNode saveRegion(String token, UUID regionId, Map<String, Object> body, int expected) throws Exception {
        String res = mvc.perform(put("/api/v1/sla-policies/regions/" + regionId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return expected == 200 ? data.read(res) : null;
    }

    @Test
    void regionalAdministratorsManageTheirOwnRegionsSla() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture other = data.site();
        String ro = data.login(mvc, data.user("RO_ADMIN", site.regionId(), null));
        String lhio = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        Map<String, Object> policy = Map.of("waitingTargetMinutes", 20, "processingTargetMinutes", 10,
                "complianceTargetPercent", 85, "serviceTargets", List.of());

        JsonNode saved = saveRegion(ro, site.regionId(), policy, 200);
        assertThat(saved.get("inherited").asBoolean()).isFalse();
        assertThat(saved.get("waitingTargetMinutes").asInt()).isEqualTo(20);
        saveRegion(ro, other.regionId(), policy, 403);   // another region
        saveRegion(lhio, site.regionId(), policy, 403);  // no SLA_MANAGE
        mvc.perform(put("/api/v1/sla-policies/national").header("Authorization", ro)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(policy))).andExpect(status().isForbidden());

        // A region without its own policy shows the national default as inherited.
        JsonNode list = data.read(mvc.perform(get("/api/v1/sla-policies").header("Authorization", ro))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode otherRow = null;
        for (JsonNode n : list) {
            if (other.regionId().toString().equals(n.path("regionId").asText())) {
                otherRow = n;
            }
        }
        assertThat(otherRow).isNotNull();
        assertThat(otherRow.get("inherited").asBoolean()).isTrue();

        mvc.perform(delete("/api/v1/sla-policies/regions/" + site.regionId()).header("Authorization", ro))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action in ('SLA_POLICY_SAVED','SLA_POLICY_REMOVED')"
                + " and details like ?", Integer.class, "%" + site.regionId() + "%")).isEqualTo(2);
    }

    @Test
    void agentPerformanceIsMeasuredAgainstTheRegionalSla() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("CO_ADMIN", null, null));
        String agentA = data.user("AGENT", null, site.siteId());
        String agentB = data.user("AGENT", null, site.siteId());
        UUID a = userId(agentA);
        UUID b = userId(agentB);
        UUID serviceA = site.serviceA();
        UUID serviceW = site.serviceB();
        UUID itemW = jdbc.queryForObject("select service_item_id from site_service where id = ?", UUID.class, serviceW);

        // Region SLA: wait 10 min, process 5 min, 80 % compliance; walk-in inquiries may take 20 min.
        saveRegion(admin, site.regionId(), Map.of("waitingTargetMinutes", 10, "processingTargetMinutes", 5,
                "complianceTargetPercent", 80,
                "serviceTargets", List.of(Map.of("serviceItemId", itemW, "processingTargetMinutes", 20))), 200);

        // T1: waited 8 (ok), served 4 (ok), completed by A.
        UUID t1 = ticket(site, serviceA, 1, "09:00", "COMPLETED");
        event(t1, site, serviceA, "ISSUE", "09:00", null, "Kiosk");
        event(t1, site, serviceA, "CALL", "09:08", a, "Agent A");
        event(t1, site, serviceA, "COMPLETE", "09:12", a, "Agent A");
        // T2: waited 20 (late); served 9:20-9:24 + 9:30-9:33 = 7 (late); the 6-minute hold does not count.
        UUID t2 = ticket(site, serviceA, 2, "09:00", "COMPLETED");
        event(t2, site, serviceA, "ISSUE", "09:00", null, "Kiosk");
        event(t2, site, serviceA, "CALL", "09:20", a, "Agent A");
        event(t2, site, serviceA, "RECALL", "09:21", a, "Agent A");
        event(t2, site, serviceA, "HOLD", "09:24", a, "Agent A");
        event(t2, site, serviceA, "RESUME", "09:30", a, "Agent A");
        event(t2, site, serviceA, "COMPLETE", "09:33", a, "Agent A");
        // T3: A serves 2 min and transfers to walk-in; B serves 15 min (within the 20-min override).
        UUID t3 = ticket(site, serviceA, 3, "09:00", "COMPLETED");
        event(t3, site, serviceA, "ISSUE", "09:00", null, "Kiosk");
        event(t3, site, serviceA, "CALL", "09:05", a, "Agent A");
        event(t3, site, serviceW, "TRANSFER", "09:07", a, "Agent A");
        event(t3, site, serviceW, "CALL", "09:10", b, "Agent B");
        event(t3, site, serviceW, "COMPLETE", "09:25", b, "Agent B");
        // T4: called after 1 min, removed as no-show (not counted in processing compliance).
        UUID t4 = ticket(site, serviceA, 4, "09:39", "REMOVED");
        event(t4, site, serviceA, "ISSUE", "09:39", null, "Kiosk");
        event(t4, site, serviceA, "CALL", "09:40", a, "Agent A");
        event(t4, site, serviceA, "REMOVE", "09:41", a, "Agent A");

        JsonNode report = data.read(mvc.perform(get("/api/v1/reports/agent-performance").header("Authorization", admin)
                        .param("from", day.toString()).param("to", day.toString()).param("siteId", site.siteId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode rowA = null;
        JsonNode rowB = null;
        for (JsonNode r : report.get("rows")) {
            if (r.get("agentId").asText().equals(a.toString())) {
                rowA = r;
            } else if (r.get("agentId").asText().equals(b.toString())) {
                rowB = r;
            }
        }
        assertThat(rowA).isNotNull();
        assertThat(rowA.get("username").asText()).isEqualTo(agentA);
        assertThat(rowA.get("handled").asLong()).isEqualTo(4);
        assertThat(rowA.get("completed").asLong()).isEqualTo(2);
        assertThat(rowA.get("transferred").asLong()).isEqualTo(1);
        assertThat(rowA.get("noShows").asLong()).isEqualTo(1);
        assertThat(rowA.get("recalls").asLong()).isEqualTo(1);
        assertThat(rowA.get("avgProcessingMinutes").asDouble()).isCloseTo(13.0 / 3, within(0.01));
        assertThat(rowA.get("processingWithinTargetPercent").asDouble()).isCloseTo(66.67, within(0.01));
        assertThat(rowA.get("avgWaitMinutes").asDouble()).isCloseTo(8.5, within(0.01));
        assertThat(rowA.get("waitingWithinTargetPercent").asDouble()).isCloseTo(75.0, within(0.01));
        assertThat(rowA.get("waitingTargetMinutes").asInt()).isEqualTo(10);
        assertThat(rowA.get("processingTargetMinutes").asInt()).isEqualTo(5);
        assertThat(rowA.get("slaStatus").asText()).isEqualTo("NOT_MET");   // 66.67 % < 80 %

        assertThat(rowB).isNotNull();
        assertThat(rowB.get("handled").asLong()).isEqualTo(1);
        assertThat(rowB.get("avgProcessingMinutes").asDouble()).isCloseTo(15.0, within(0.01));
        assertThat(rowB.get("processingWithinTargetPercent").asDouble()).isEqualTo(100.0);
        assertThat(rowB.get("serviceOverrides").asBoolean()).isTrue();
        assertThat(rowB.get("slaStatus").asText()).isEqualTo("MET");

        // Downloads.
        String csv = new String(mvc.perform(get("/api/v1/reports/agent-performance/export").header("Authorization", admin)
                        .param("from", day.toString()).param("to", day.toString()).param("siteId", site.siteId().toString())
                        .param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Agent A\"", "\"NOT MET\"", "\"Agent B\"", "\"MET\"");
        byte[] pdf = mvc.perform(get("/api/v1/reports/agent-performance/export").header("Authorization", admin)
                        .param("from", day.toString()).param("to", day.toString()).param("siteId", site.siteId().toString())
                        .param("format", "pdf"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        // Excel: the full workbook of every agent, including one with no activity in the period.
        String idle = data.user("AGENT", null, site.siteId());
        byte[] xlsx = mvc.perform(get("/api/v1/reports/agent-performance/export").header("Authorization", admin)
                        .param("from", day.toString()).param("to", day.toString()).param("siteId", site.siteId().toString())
                        .param("format", "xlsx"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            assertThat(java.util.stream.IntStream.range(0, wb.getNumberOfSheets()).mapToObj(wb::getSheetName))
                    .containsExactly("Summary", "Daily breakdown", "Transactions", "SLA targets", "Definitions");
            var summary = wb.getSheet("Summary");
            Map<String, String> statusByUser = new java.util.HashMap<>();
            for (int r = 4; r <= summary.getLastRowNum(); r++) {
                var row = summary.getRow(r);
                statusByUser.put(row.getCell(1).getStringCellValue(), row.getCell(17).getStringCellValue());
            }
            assertThat(statusByUser).containsEntry(agentA, "NOT MET").containsEntry(agentB, "MET")
                    .containsEntry(idle, "NO DATA");
            // Percentages are real Excel percentages (0.6667 shown as 66.7%).
            for (int r = 4; r <= summary.getLastRowNum(); r++) {
                var row = summary.getRow(r);
                if (agentA.equals(row.getCell(1).getStringCellValue())) {
                    assertThat(row.getCell(10).getNumericCellValue()).isCloseTo(0.6667, within(0.001));
                }
            }
            assertThat(wb.getSheet("Transactions").getLastRowNum() - 3).isEqualTo(5);   // A: 4, B: 1
            assertThat(wb.getSheet("Daily breakdown").getLastRowNum() - 3).isEqualTo(2);
        }

        // An agent cannot view the report.
        mvc.perform(get("/api/v1/reports/agent-performance").header("Authorization", data.login(mvc, agentA))
                .param("from", day.toString()).param("to", day.toString())).andExpect(status().isForbidden());
    }
}

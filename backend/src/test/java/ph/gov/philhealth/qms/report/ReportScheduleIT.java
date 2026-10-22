package ph.gov.philhealth.qms.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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

/** Scheduled reports, ticket-level export and audit export through the REST API. */
@IntegrationTest
class ReportScheduleIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReportScheduleService schedules;

    private final LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(1);

    /** Completed tickets on a past business date, inserted directly (the queue API only issues for today). */
    private void pastTickets(TestData.SiteFixture site, int count) {
        for (int i = 1; i <= count; i++) {
            Instant issued = yesterday.atTime(9, 0).atZone(ZoneId.of("Asia/Manila")).toInstant().plusSeconds(i * 60L);
            jdbc.update("""
                    insert into ticket (id, site_id, site_service_id, original_site_service_id, business_date, prefix,
                        seq_no, display_number, channel, status, issued_at, queued_at, first_called_at, completed_at,
                        status_changed_at, wait_seconds, service_seconds)
                    values (?, ?, ?, ?, ?, 'A', ?, ?, 'PRINTED', 'COMPLETED', ?, ?, ?, ?, ?, 600, 300)
                    """, Ids.newId(), site.siteId(), site.serviceA(), site.serviceA(), yesterday, i,
                    "A-%03d".formatted(i), java.sql.Timestamp.from(issued), java.sql.Timestamp.from(issued),
                    java.sql.Timestamp.from(issued.plusSeconds(600)), java.sql.Timestamp.from(issued.plusSeconds(900)),
                    java.sql.Timestamp.from(issued.plusSeconds(900)));
        }
    }

    private Map<String, Object> schedule(String name, UUID siteId, String format) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("frequency", "DAILY");
        m.put("groupBy", "SERVICE");
        m.put("format", format);
        m.put("siteId", siteId);
        m.put("active", true);
        return m;
    }

    @Test
    void scheduledReportsRespectScopeAndProduceDownloadableFiles() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture other = data.site();
        pastTickets(site, 4);
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        String otherAdmin = data.login(mvc, data.user("LHIO_ADMIN", null, other.siteId()));

        // Scope: own site only; no national schedules for a site administrator.
        mvc.perform(post("/api/v1/report-schedules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(data.json(schedule("Other", other.siteId(), "CSV")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/report-schedules").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(data.json(schedule("National", null, "CSV")))).andExpect(status().isForbidden());

        JsonNode created = data.read(mvc.perform(post("/api/v1/report-schedules").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(schedule("Daily CSV", site.siteId(), "CSV"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.siteName").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String id = created.get("id").asText();
        assertThat(Instant.parse(created.get("nextRunAt").asText())).isAfter(Instant.now());

        // Run now: yesterday's 4 tickets end up in the file.
        JsonNode run = data.read(mvc.perform(post("/api/v1/report-schedules/" + id + "/run-now").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString());
        assertThat(run.get("periodFrom").asText()).isEqualTo(yesterday.toString());
        String csv = new String(mvc.perform(get("/api/v1/report-runs/" + run.get("id").asText() + "/download")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Daily-CSV")))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Total\",\"4\",\"4\"");

        // Another site's administrator can neither see nor download it.
        mvc.perform(get("/api/v1/report-schedules").header("Authorization", otherAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
        mvc.perform(get("/api/v1/report-runs/" + run.get("id").asText() + "/download").header("Authorization", otherAdmin))
                .andExpect(status().isForbidden());

        // The background job picks up due schedules and moves them to the next period.
        jdbc.update("update report_schedule set next_run_at = now() - interval '1 minute' where id = ?::uuid", id);
        schedules.runDue();
        assertThat(jdbc.queryForObject("select count(*) from report_run where schedule_id = ?::uuid and trigger = 'SCHEDULED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_run_at > now() from report_schedule where id = ?::uuid",
                Boolean.class, id)).isTrue();
        // Running again immediately does nothing: it is no longer due.
        schedules.runDue();
        assertThat(jdbc.queryForObject("select count(*) from report_run where schedule_id = ?::uuid and trigger = 'SCHEDULED'",
                Integer.class, id)).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'REPORT_DOWNLOADED' and entity_id = ?",
                Integer.class, run.get("id").asText())).isEqualTo(1);
    }

    @Test
    void pdfAndXlsxSchedulesGenerateValidFiles() throws Exception {
        TestData.SiteFixture site = data.site();
        pastTickets(site, 2);
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        for (String format : new String[] {"PDF", "XLSX"}) {
            String id = data.read(mvc.perform(post("/api/v1/report-schedules").header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content(data.json(schedule("F " + format, site.siteId(), format))))
                    .andReturn().getResponse().getContentAsString()).get("id").asText();
            String runId = data.read(mvc.perform(post("/api/v1/report-schedules/" + id + "/run-now").header("Authorization", admin))
                    .andReturn().getResponse().getContentAsString()).get("id").asText();
            byte[] file = mvc.perform(get("/api/v1/report-runs/" + runId + "/download").header("Authorization", admin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            // PDF files start with %PDF; XLSX files are ZIP archives (PK).
            assertThat(new String(file, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo(format.equals("PDF") ? "%P" : "PK");
        }
    }

    @Test
    void ticketLevelAndAuditExports() throws Exception {
        TestData.SiteFixture site = data.site();
        pastTickets(site, 3);
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        String csv = new String(mvc.perform(get("/api/v1/reports/tickets/export").header("Authorization", admin)
                        .param("from", yesterday.toString()).param("to", yesterday.toString())
                        .param("siteId", site.siteId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).isEqualTo(4);   // header + 3 tickets
        assertThat(csv).contains("\"A-001\"", "\"COMPLETED\"");

        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        mvc.perform(get("/api/v1/reports/tickets/export").header("Authorization", agent)
                .param("from", yesterday.toString()).param("to", yesterday.toString())).andExpect(status().isForbidden());

        String audit = new String(mvc.perform(get("/api/v1/audit/export").header("Authorization", admin)
                        .param("from", yesterday.toString()).param("to", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(audit).startsWith("﻿Time (UTC),Actor,Action").contains("REPORT_EXPORTED");
    }
}

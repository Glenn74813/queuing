package ph.gov.philhealth.qms.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.queue.QueueService;
import ph.gov.philhealth.qms.queue.Ticket;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Prometheus endpoint protection and the QMS-specific metrics it exposes. */
@IntegrationTest
@AutoConfigureObservability   // Spring Boot disables metric exporters in tests unless asked
class MetricsIT {

    private static final String TOKEN = "test-metrics-token-0123456789abcdef";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    QueueService queue;
    @Autowired
    QmsMetrics metrics;

    @Test
    void scrapeRequiresTheMetricsTokenOrSystemMonitor() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        // The token only opens the metrics endpoint, nothing else.
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        String agent = data.login(mvc, data.user("AGENT", null, data.site().siteId()));
        mvc.perform(get("/actuator/prometheus").header("Authorization", agent)).andExpect(status().isForbidden());
    }

    @Test
    void exposesQueueMetrics() throws Exception {
        TestData.SiteFixture site = data.site();
        DevicePrincipal kiosk = new DevicePrincipal(site.kioskId(), site.siteId(), "K", "KIOSK");
        queue.issue(kiosk, site.serviceA(), null, Ticket.Channel.PRINTED, Ids.newId());
        queue.issue(kiosk, site.serviceA(), null, Ticket.Channel.ELECTRONIC, Ids.newId());
        metrics.refreshGauges();

        String body = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "qms-prometheus-sample.txt"), body);

        assertThat(body).contains("qms_tickets_issued_total{");
        assertThat(line(body, "qms_tickets_issued_total{", "site=\"" + site.siteCode() + "\"", "channel=\"PRINTED\""))
                .endsWith(" 1.0");
        assertThat(line(body, "qms_queue_waiting_tickets{", "site=\"" + site.siteCode() + "\"")).endsWith(" 2.0");
        assertThat(body).contains("qms_ticket_actions_total{", "action=\"ISSUE\"");
        assertThat(body).contains("qms_sync_outbox_entries{", "qms_sync_outbox_oldest_pending_age_seconds");
        // Standard JVM and HTTP metrics (Hikari pool metrics appear in production; the test datasource is not Hikari).
        assertThat(body).contains("jvm_memory_used_bytes", "http_server_requests");
    }

    private static String line(String body, String... parts) {
        return body.lines().filter(l -> {
            for (String p : parts) {
                if (!l.contains(p)) {
                    return false;
                }
            }
            return true;
        }).findFirst().orElseThrow(() -> new AssertionError("No metric line with " + String.join(" ", parts)));
    }

}

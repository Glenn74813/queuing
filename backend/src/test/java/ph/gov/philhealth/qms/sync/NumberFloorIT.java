package ph.gov.philhealth.qms.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** A site node restored from an older backup continues numbering above what central already holds. */
@IntegrationTest
class NumberFloorIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ConfigSyncService config;

    private String issue(TestData.SiteFixture site) throws Exception {
        return data.read(mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", site.serviceA(),
                                "channel", "PRINTED", "requestId", UUID.randomUUID()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("displayNumber").asText();
    }

    private void pullFloor(TestData.SiteFixture site, LocalDate day, int last) {
        config.apply(new ConfigBundle(Instant.now(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ConfigBundle.NumberFloor(site.siteId(), "A", day, last))));
    }

    @Test
    void floorFromCentralRaisesButNeverLowersTheSequence() throws Exception {
        TestData.SiteFixture site = data.site();
        assertThat(issue(site)).isEqualTo("A-001");
        LocalDate today = jdbc.queryForObject("select max(business_date) from ticket where site_id = ?", LocalDate.class,
                site.siteId());
        // Central already holds up to A-040 for today (issued before this node was restored).
        pullFloor(site, today, 40);
        assertThat(issue(site)).isEqualTo("A-041");
        pullFloor(site, today, 5);
        assertThat(issue(site)).isEqualTo("A-042");
    }
}

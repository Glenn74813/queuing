package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Dashboard overview across sites: national roles see every site, others only their scope. */
@IntegrationTest
class OverviewIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    private void issue(TestData.SiteFixture site, UUID service) throws Exception {
        mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", service,
                        "channel", "PRINTED", "requestId", UUID.randomUUID())))).andExpect(status().isOk());
    }

    private JsonNode overview(String token) throws Exception {
        return data.read(mvc.perform(get("/api/v1/dashboard/overview").param("days", "1").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> siteIds(JsonNode o) {
        List<String> ids = new ArrayList<>();
        o.get("sites").forEach(s -> ids.add(s.get("siteId").asText()));
        return ids;
    }

    @Test
    void nationalRolesSeeEverySiteAndOthersOnlyTheirScope() throws Exception {
        TestData.SiteFixture a = data.site();
        TestData.SiteFixture b = data.site();
        issue(a, a.serviceA());
        issue(a, a.serviceA());
        issue(b, b.serviceA());
        issue(b, b.serviceB());

        JsonNode national = overview(data.login(mvc, data.user("SUPER_ADMIN", null, null)));
        assertThat(national.get("national").asBoolean()).isTrue();
        assertThat(siteIds(national)).contains(a.siteId().toString(), b.siteId().toString());
        for (JsonNode s : national.get("sites")) {
            if (s.get("siteId").asText().equals(a.siteId().toString())) {
                assertThat(s.get("issuedToday").asLong()).isEqualTo(2);
                assertThat(s.get("waitingNow").asLong()).isEqualTo(2);
                assertThat(s.get("countersTotal").asLong()).isEqualTo(2);
            }
        }
        // Totals add up over the sites, and services are summed across sites by catalog service.
        long sum = 0;
        for (JsonNode s : national.get("sites")) {
            sum += s.get("issuedToday").asLong();
        }
        assertThat(national.get("totals").get("issuedToday").asLong()).isEqualTo(sum);
        JsonNode pmrf = null;
        for (JsonNode s : national.get("services")) {
            if ("SVC-01".equals(s.get("serviceCode").asText())) {
                pmrf = s;
            }
        }
        assertThat(pmrf).isNotNull();
        assertThat(pmrf.get("issued").asLong()).isGreaterThanOrEqualTo(3);   // 2 at site a + 1 at site b
        assertThat(pmrf.get("sitesOffering").asLong()).isGreaterThanOrEqualTo(2);

        // A regional administrator sees only the sites of their region; an agent only their site.
        JsonNode regional = overview(data.login(mvc, data.user("RO_ADMIN", a.regionId(), null)));
        assertThat(regional.get("national").asBoolean()).isFalse();
        assertThat(siteIds(regional)).containsExactly(a.siteId().toString());
        JsonNode agent = overview(data.login(mvc, data.user("AGENT", null, b.siteId())));
        assertThat(siteIds(agent)).containsExactly(b.siteId().toString());
        assertThat(agent.get("totals").get("issuedToday").asLong()).isEqualTo(2);
    }
}

package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Dashboard "Services availed": clients per service, every offered service included. */
@IntegrationTest
class ServiceVolumeIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    private String issue(TestData.SiteFixture site, UUID service) throws Exception {
        return data.read(mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", service,
                                "channel", "PRINTED", "requestId", UUID.randomUUID()))))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void countsClientsPerServiceIncludingUnusedServices() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture other = data.site();
        issue(site, site.serviceA());
        issue(site, site.serviceA());
        String voided = issue(site, site.serviceA());
        String supervisor = data.login(mvc, data.user("SUPERVISOR", null, site.siteId()));
        mvc.perform(post("/api/v1/tickets/" + voided + "/void").header("Authorization", supervisor)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("reason", "Issued in error"))))
                .andExpect(status().isOk());

        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        JsonNode report = data.read(mvc.perform(get("/api/v1/sites/" + site.siteId() + "/service-volume")
                        .param("days", "7").header("Authorization", agent))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode services = report.get("services");
        assertThat(services).hasSize(2);   // both offered services, the unused one at zero
        assertThat(services.get(0).get("siteServiceId").asText()).isEqualTo(site.serviceA().toString());
        assertThat(services.get(0).get("issued").asLong()).isEqualTo(2);   // voided ticket excluded
        assertThat(services.get(0).get("waiting").asLong()).isEqualTo(2);
        assertThat(services.get(1).get("issued").asLong()).isZero();

        // Site scope applies.
        mvc.perform(get("/api/v1/sites/" + other.siteId() + "/service-volume").header("Authorization", agent))
                .andExpect(status().isForbidden());
    }
}

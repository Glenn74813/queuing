package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Multi-stage routing: configured next stage, loop prevention and the one-click "next stage" action. */
@IntegrationTest
class RoutingIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    private JsonNode siteService(TestData.SiteFixture site, String admin, String id) throws Exception {
        String body = mvc.perform(get("/api/v1/sites/" + site.siteId() + "/services").header("Authorization", admin))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : data.read(body)) {
            if (s.get("id").asText().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("site service not found");
    }

    private org.springframework.test.web.servlet.ResultActions setNext(TestData.SiteFixture site, String admin,
                                                                        UUID serviceId, UUID next) throws Exception {
        JsonNode s = siteService(site, admin, serviceId.toString());
        Map<String, Object> in = new HashMap<>();
        in.put("serviceItemId", s.get("serviceItemId").asText());
        in.put("prefix", s.get("prefix").asText());
        in.put("active", true);
        in.put("sortOrder", s.get("sortOrder").asInt());
        in.put("estimatedDurationMinutes", s.get("estimatedDurationMinutes").asInt());
        in.put("nextSiteServiceId", next);
        return mvc.perform(put("/api/v1/sites/" + site.siteId() + "/services/" + serviceId).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(in)));
    }

    @Test
    void ticketsMoveToTheConfiguredNextStage() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture other = data.site();
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));

        // A → W is allowed; W → A would loop; self and other-site targets are rejected.
        setNext(site, admin, site.serviceA(), site.serviceB()).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextSiteServiceId").value(site.serviceB().toString()));
        setNext(site, admin, site.serviceB(), site.serviceA()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("loop")));
        setNext(site, admin, site.serviceA(), site.serviceA()).andExpect(status().isBadRequest());
        setNext(site, admin, site.serviceA(), other.serviceA()).andExpect(status().isBadRequest());

        // Issue at A, call it: the ticket shows where it goes next.
        mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", site.serviceA(),
                        "channel", "PRINTED", "requestId", UUID.randomUUID())))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", site.counter1()))))
                .andExpect(status().isOk());
        JsonNode called = data.read(mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(called.get("nextSiteServiceId").asText()).isEqualTo(site.serviceB().toString());
        assertThat(called.get("nextServiceName").asText()).isEqualTo("Walk-in client inquiries");

        String id = called.get("id").asText();
        JsonNode moved = data.read(mvc.perform(post("/api/v1/tickets/" + id + "/next-stage").header("Authorization", agent))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(moved.get("status").asText()).isEqualTo("WAITING");
        assertThat(moved.get("siteServiceId").asText()).isEqualTo(site.serviceB().toString());
        assertThat(moved.get("stageNo").asInt()).isEqualTo(2);
        assertThat(moved.has("nextSiteServiceId")).isFalse();   // last stage

        // At the last stage there is nowhere to go.
        JsonNode again = data.read(mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent))
                .andReturn().getResponse().getContentAsString());
        assertThat(again.get("id").asText()).isEqualTo(id);
        mvc.perform(post("/api/v1/tickets/" + id + "/next-stage").header("Authorization", agent))
                .andExpect(status().isConflict());

        JsonNode detail = data.read(mvc.perform(get("/api/v1/tickets/" + id).header("Authorization", agent))
                .andReturn().getResponse().getContentAsString());
        assertThat(detail.get("events").toString()).contains("Proceeds to next stage: Walk-in client inquiries");
    }
}

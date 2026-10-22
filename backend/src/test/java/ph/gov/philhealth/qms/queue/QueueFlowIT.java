package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** End-to-end queue operations through the REST API: kiosk → agent console → reports. */
@IntegrationTest
class QueueFlowIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    TestData.SiteFixture site;
    String agent;
    String supervisor;

    @BeforeEach
    void setUp() throws Exception {
        site = data.site();
        agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        supervisor = data.login(mvc, data.user("SUPERVISOR", null, site.siteId()));
    }

    private JsonNode issue(UUID service, UUID priority, UUID requestId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("siteServiceId", service);
        body.put("channel", "PRINTED");
        body.put("requestId", requestId);
        if (priority != null) {
            body.put("priorityCategoryId", priority);
        }
        String res = mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data.read(res);
    }

    private JsonNode agentPost(String path, Object body) throws Exception {
        var req = post(path).header("Authorization", agent).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req.content(data.json(body));
        }
        String res = mvc.perform(req).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : data.read(res);
    }

    @Test
    void fullTicketLifecycle() throws Exception {
        // Kiosk sees the configured services.
        mvc.perform(get("/api/v1/kiosk/config").header("X-Device-Key", site.kioskKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(2))
                .andExpect(jsonPath("$.priorityCategories.length()").value(3));

        UUID req1 = UUID.randomUUID();
        JsonNode t1 = issue(site.serviceA(), null, req1);
        assertThat(t1.get("displayNumber").asText()).isEqualTo("A-001");
        assertThat(t1.get("waitingAhead").asLong()).isZero();
        assertThat(t1.get("siteName").asText()).contains(site.siteCode());

        // Retrying the same request does not issue a second ticket.
        JsonNode retry = issue(site.serviceA(), null, req1);
        assertThat(retry.get("id").asText()).isEqualTo(t1.get("id").asText());

        JsonNode t2 = issue(site.serviceA(), null, UUID.randomUUID());
        assertThat(t2.get("displayNumber").asText()).isEqualTo("A-002");
        String seniorId = data.read(mvc.perform(get("/api/v1/kiosk/config").header("X-Device-Key", site.kioskKey()))
                .andReturn().getResponse().getContentAsString()).get("priorityCategories").get(0).get("id").asText();
        JsonNode t3 = issue(site.serviceA(), UUID.fromString(seniorId), UUID.randomUUID());
        assertThat(t3.get("displayNumber").asText()).isEqualTo("A-003");
        assertThat(t3.get("priorityCode").asText()).isNotBlank();

        // Agent signs in at counter 1; priority ticket A-003 is called before A-001.
        agentPost("/api/v1/agent/session/start", Map.of("counterId", site.counter1()));
        JsonNode called = agentPost("/api/v1/agent/call-next", null);
        assertThat(called.get("displayNumber").asText()).isEqualTo("A-003");
        assertThat(called.get("status").asText()).isEqualTo("CALLED");
        assertThat(called.get("counterName").asText()).isEqualTo("Counter 1");

        // Cannot call another while one is at the counter; cannot complete a waiting ticket.
        mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tickets/" + t1.get("id").asText() + "/complete").header("Authorization", agent))
                .andExpect(status().isForbidden());   // not at this agent's counter

        String id3 = called.get("id").asText();
        assertThat(agentPost("/api/v1/tickets/" + id3 + "/recall", null).get("recallCount").asInt()).isEqualTo(1);
        assertThat(agentPost("/api/v1/tickets/" + id3 + "/hold", Map.of("reason", "Missing ID")).get("status").asText())
                .isEqualTo("ON_HOLD");
        assertThat(agentPost("/api/v1/tickets/" + id3 + "/resume", null).get("status").asText()).isEqualTo("CALLED");
        // Hold requires a reason.
        mvc.perform(post("/api/v1/tickets/" + id3 + "/hold").header("Authorization", agent)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));

        // Multi-stage: transfer to the second service, then complete there.
        JsonNode moved = agentPost("/api/v1/tickets/" + id3 + "/transfer",
                Map.of("targetSiteServiceId", site.serviceB(), "reason", "Needs records"));
        assertThat(moved.get("status").asText()).isEqualTo("WAITING");
        assertThat(moved.get("stageNo").asInt()).isEqualTo(2);

        JsonNode next = agentPost("/api/v1/agent/call-next", null);
        assertThat(next.get("displayNumber").asText()).isEqualTo("A-003");   // still highest priority
        assertThat(agentPost("/api/v1/tickets/" + id3 + "/complete", null).get("status").asText()).isEqualTo("COMPLETED");

        // A-001 called and removed as no-show; A-002 voided by supervisor.
        JsonNode a1 = agentPost("/api/v1/agent/call-next", null);
        assertThat(a1.get("displayNumber").asText()).isEqualTo("A-001");
        agentPost("/api/v1/tickets/" + a1.get("id").asText() + "/remove", Map.of("reason", "No-show"));
        mvc.perform(post("/api/v1/tickets/" + t2.get("id").asText() + "/void").header("Authorization", agent)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("reason", "x"))))
                .andExpect(status().isForbidden());   // agents lack TICKET_VOID
        mvc.perform(post("/api/v1/tickets/" + t2.get("id").asText() + "/void").header("Authorization", supervisor)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("reason", "Duplicate"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));

        // Queue now empty.
        mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent)).andExpect(status().isNoContent());

        // Event history is complete and attributed.
        JsonNode detail = data.read(mvc.perform(get("/api/v1/tickets/" + id3).header("Authorization", supervisor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("events")).extracting(e -> e.get("type").asText()).containsExactly(
                "ISSUE", "CALL", "RECALL", "HOLD", "RESUME", "TRANSFER", "CALL", "COMPLETE");

        // Client feedback only for completed tickets, once.
        mvc.perform(post("/api/v1/kiosk/feedback").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("ticketId", id3, "rating", 5))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/kiosk/feedback").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("ticketId", id3, "rating", 4))))
                .andExpect(status().isConflict());

        // Display board shows nothing serving and recent calls.
        mvc.perform(get("/api/v1/display/board").header("X-Device-Key", site.displayKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nowServing.length()").value(0))
                .andExpect(jsonPath("$.recentCalls.length()").value(2));
        // A kiosk key cannot read the display board.
        mvc.perform(get("/api/v1/display/board").header("X-Device-Key", site.kioskKey()))
                .andExpect(status().isForbidden());

        // Report reconciles with what happened.
        String today = LocalDate.now(ZoneId.of("Asia/Manila")).toString();
        String reporter = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        JsonNode report = data.read(mvc.perform(get("/api/v1/reports/summary").header("Authorization", reporter)
                        .param("from", today).param("to", today).param("siteId", site.siteId().toString())
                        .param("groupBy", "SERVICE"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode totals = report.get("totals");
        assertThat(totals.get("issued").asLong()).isEqualTo(3);
        assertThat(totals.get("served").asLong()).isEqualTo(1);
        assertThat(totals.get("abandoned").asLong()).isEqualTo(1);
        assertThat(totals.get("voided").asLong()).isEqualTo(1);
        assertThat(totals.get("rerouted").asLong()).isEqualTo(1);
        assertThat(totals.get("feedbackCount").asLong()).isEqualTo(1);
        assertThat(totals.get("avgRating").asDouble()).isEqualTo(5.0);

        mvc.perform(get("/api/v1/reports/summary/export").header("Authorization", reporter)
                        .param("from", today).param("to", today).param("siteId", site.siteId().toString())
                        .param("format", "xlsx"))
                .andExpect(status().isOk());

        agentPost("/api/v1/agent/session/end", null);
    }

    @Test
    void agentCannotEndSessionWithTicketAtCounter() throws Exception {
        issue(site.serviceB(), null, UUID.randomUUID());
        agentPost("/api/v1/agent/session/start", Map.of("counterId", site.counter2()));
        agentPost("/api/v1/agent/call-next", null);
        mvc.perform(post("/api/v1/agent/session/end").header("Authorization", agent)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/agent/session/break/start").header("Authorization", agent))
                .andExpect(status().isConflict());

        // Supervisor closes the counter: the ticket goes back to the queue, not lost.
        mvc.perform(post("/api/v1/sites/" + site.siteId() + "/counters/" + site.counter2() + "/force-end-session")
                        .header("Authorization", supervisor).contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("reason", "Agent unwell"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/sites/" + site.siteId() + "/queue").header("Authorization", supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting.length()").value(1))
                .andExpect(jsonPath("$.called.length()").value(0));
        mvc.perform(get("/api/v1/agent/session").header("Authorization", agent)).andExpect(status().isNoContent());
    }

    @Test
    void secondAgentCannotTakeOccupiedCounter() throws Exception {
        agentPost("/api/v1/agent/session/start", Map.of("counterId", site.counter1()));
        String other = data.login(mvc, data.user("AGENT", null, site.siteId()));
        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", site.counter1()))))
                .andExpect(status().isConflict());
    }
}

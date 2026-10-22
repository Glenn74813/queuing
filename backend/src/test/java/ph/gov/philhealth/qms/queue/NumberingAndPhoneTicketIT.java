package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Site numbering format and reset schedule; the phone e-ticket link and its feedback; kiosk feedback lookup. */
@IntegrationTest
class NumberingAndPhoneTicketIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode issue(TestData.SiteFixture site, UUID service) throws Exception {
        return data.read(mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", service,
                                "channel", "ELECTRONIC", "requestId", UUID.randomUUID()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void periodStartFollowsTheResetSchedule() {
        LocalDate wed = LocalDate.of(2026, 10, 21);
        assertThat(TicketNumberAllocator.periodStart(wed, TicketNumberAllocator.Reset.DAILY)).isEqualTo(wed);
        assertThat(TicketNumberAllocator.periodStart(wed, TicketNumberAllocator.Reset.WEEKLY)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(TicketNumberAllocator.periodStart(wed, TicketNumberAllocator.Reset.MONTHLY)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(TicketNumberAllocator.periodStart(wed, TicketNumberAllocator.Reset.YEARLY)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(TicketNumberAllocator.format("AB", 7, "", 4)).isEqualTo("AB0007");
    }

    @Test
    void siteFormatAndMonthlyResetApplyWithoutRepeatingTodaysNumbers() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("SUPER_ADMIN", null, null));
        assertThat(issue(site, site.serviceA()).get("displayNumber").asText()).isEqualTo("A-001");
        assertThat(issue(site, site.serviceA()).get("displayNumber").asText()).isEqualTo("A-002");

        // Earlier this month the monthly sequence (keyed by the 1st) had reached 1; today's daily numbers
        // are already at 2, so switching to monthly must continue at 3, not repeat 2.
        LocalDate first = jdbc.queryForObject("select business_date from ticket where site_id = ? limit 1",
                LocalDate.class, site.siteId()).withDayOfMonth(1);
        jdbc.update("""
                insert into ticket_sequence (site_id, prefix, business_date, last_value) values (?, 'A', ?, 1)
                on conflict do nothing""", site.siteId(), first);
        JsonNode s = data.read(mvc.perform(get("/api/v1/sites").header("Authorization", admin))
                .andReturn().getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode x : s) {
            if (x.get("id").asText().equals(site.siteId().toString())) {
                mine = x;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("ticketNumberReset").asText()).isEqualTo("DAILY");
        Map<String, Object> in = new HashMap<>();
        in.put("code", mine.get("code").asText());
        in.put("name", mine.get("name").asText());
        in.put("type", mine.get("type").asText());
        in.put("regionId", mine.get("regionId").asText());
        in.put("timezone", mine.get("timezone").asText());
        in.put("ticketNumberDigits", 4);
        in.put("ticketNumberSeparator", "");
        in.put("ticketNumberReset", "MONTHLY");
        mvc.perform(put("/api/v1/sites/" + site.siteId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(in)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketNumberReset").value("MONTHLY"));
        assertThat(issue(site, site.serviceA()).get("displayNumber").asText()).isEqualTo("A0003");
        assertThat(issue(site, site.serviceA()).get("displayNumber").asText()).isEqualTo("A0004");

        in.put("ticketNumberDigits", 9);
        mvc.perform(put("/api/v1/sites/" + site.siteId()).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(in))).andExpect(status().isBadRequest());
    }

    @Test
    void phoneLinkShowsProgressAndTakesFeedbackOnce() throws Exception {
        TestData.SiteFixture site = data.site();
        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        JsonNode t = issue(site, site.serviceA());
        String token = t.get("publicToken").asText();
        assertThat(token).hasSizeGreaterThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("select public_token_hash from ticket where id = ?::uuid", String.class,
                t.get("id").asText())).isNotEqualTo(token);

        mvc.perform(get("/api/v1/public/tickets/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.displayNumber").value(t.get("displayNumber").asText()))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.feedbackOpen").value(false));
        mvc.perform(get("/api/v1/public/tickets/not-a-real-token-123456")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/tickets/" + token + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("rating", 5)))).andExpect(status().isConflict());

        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", site.counter1()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/tickets/" + token)).andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.counterName").value("Counter 1"));
        mvc.perform(post("/api/v1/tickets/" + t.get("id").asText() + "/complete").header("Authorization", agent))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/tickets/" + token)).andExpect(jsonPath("$.feedbackOpen").value(true));

        mvc.perform(post("/api/v1/public/tickets/" + token + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("rating", 4, "comment", "Fast")))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/tickets/" + token)).andExpect(jsonPath("$.feedbackGiven").value(true))
                .andExpect(jsonPath("$.feedbackOpen").value(false));
        mvc.perform(post("/api/v1/public/tickets/" + token + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("rating", 1)))).andExpect(status().isConflict());
    }

    @Test
    void kioskFindsTodaysCompletedTicketByNumber() throws Exception {
        TestData.SiteFixture site = data.site();
        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        JsonNode t = issue(site, site.serviceA());
        String number = t.get("displayNumber").asText();
        mvc.perform(get("/api/v1/kiosk/feedback-ticket").param("number", number).header("X-Device-Key", site.kioskKey()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", site.counter1()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/call-next").header("Authorization", agent)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/tickets/" + t.get("id").asText() + "/complete").header("Authorization", agent))
                .andExpect(status().isOk());

        // Typed without the hyphen and in lower case.
        mvc.perform(get("/api/v1/kiosk/feedback-ticket").param("number", number.replace("-", "").toLowerCase())
                        .header("X-Device-Key", site.kioskKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketId").value(t.get("id").asText()));
        mvc.perform(get("/api/v1/kiosk/feedback-ticket").param("number", number)
                .header("X-Device-Key", site.displayKey())).andExpect(status().isForbidden());
    }
}

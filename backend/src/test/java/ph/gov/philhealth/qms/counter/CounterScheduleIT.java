package ph.gov.philhealth.qms.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Counter opening hours: kiosks offer a service only while an eligible counter is open or staffed. */
@IntegrationTest
class CounterScheduleIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final int today = LocalDate.now(ZoneId.of("Asia/Manila")).getDayOfWeek().getValue();
    private final int otherDay = today % 7 + 1;

    private ResultActions setHours(TestData.SiteFixture site, String admin, UUID counter, String code,
                                   List<Map<String, Object>> hours) throws Exception {
        Map<String, Object> in = new HashMap<>();
        in.put("code", code);
        in.put("name", "Counter " + code);
        in.put("active", true);
        in.put("siteServiceIds", List.of(site.serviceA(), site.serviceB()));
        in.put("hours", hours);
        return mvc.perform(put("/api/v1/sites/" + site.siteId() + "/counters/" + counter).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(in)));
    }

    private static Map<String, Object> window(int day, String open, String close) {
        return Map.of("dayOfWeek", day, "openTime", open, "closeTime", close);
    }

    private boolean kioskOffersA(TestData.SiteFixture site) throws Exception {
        JsonNode cfg = data.read(mvc.perform(get("/api/v1/kiosk/config").header("X-Device-Key", site.kioskKey()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode s : cfg.get("services")) {
            if (s.get("siteServiceId").asText().equals(site.serviceA().toString())) {
                return s.get("open").asBoolean();
            }
        }
        throw new AssertionError("service A missing");
    }

    private ResultActions issueA(TestData.SiteFixture site) throws Exception {
        return mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("siteServiceId", site.serviceA(),
                        "channel", "PRINTED", "requestId", UUID.randomUUID()))));
    }

    @Test
    void scheduleClosesServicesUntilACounterIsOpenOrStaffed() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        assertThat(kioskOffersA(site)).isTrue();   // no schedule: always available

        // Both counters only open on another weekday: nobody serves today.
        setHours(site, admin, site.counter1(), "C1", List.of(window(otherDay, "08:00", "12:00"),
                window(otherDay, "13:00", "17:00"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleState").value("CLOSED"))
                .andExpect(jsonPath("$.hours.length()").value(2));
        setHours(site, admin, site.counter2(), "C2", List.of(window(otherDay, "08:00", "17:00")))
                .andExpect(status().isOk());
        // Stored as entered, whatever the JVM time zone (Hibernate must not shift wall-clock times).
        assertThat(jdbc.queryForObject("select open_time::text || '-' || close_time::text from counter_hours"
                + " where counter_id = ?", String.class, site.counter2())).isEqualTo("08:00:00-17:00:00");
        assertThat(kioskOffersA(site)).isFalse();
        issueA(site).andExpect(status().isConflict());

        // An agent staffing counter 1 anyway (overtime) makes the service available again.
        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", site.counter1()))))
                .andExpect(status().isOk());
        assertThat(kioskOffersA(site)).isTrue();
        issueA(site).andExpect(status().isOk());
        mvc.perform(post("/api/v1/agent/session/end").header("Authorization", agent)).andExpect(status().isNoContent());
        assertThat(kioskOffersA(site)).isFalse();

        // Counter 2 open all day today: available without an agent.
        setHours(site, admin, site.counter2(), "C2", List.of(window(today, "00:00", "23:59:59")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scheduleState").value("OPEN"));
        assertThat(kioskOffersA(site)).isTrue();

        // Removing the schedule (empty list) returns to "always available".
        setHours(site, admin, site.counter1(), "C1", List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleState").value("NO_SCHEDULE"));
    }

    @Test
    void invalidHoursAreRejected() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        setHours(site, admin, site.counter1(), "C1", List.of(window(today, "12:00", "08:00")))
                .andExpect(status().isBadRequest());
        setHours(site, admin, site.counter1(), "C1", List.of(window(today, "08:00", "12:00"), window(today, "11:00", "15:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Opening hours on the same day must not overlap"));
        setHours(site, admin, site.counter1(), "C1", List.of(window(9, "08:00", "12:00")))
                .andExpect(status().isBadRequest());
    }
}

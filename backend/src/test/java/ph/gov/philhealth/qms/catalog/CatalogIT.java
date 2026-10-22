package ph.gov.philhealth.qms.catalog;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Catalog administration: effective dating, versioned history and national-only editing. */
@IntegrationTest
class CatalogIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Map<String, Object> item(String code, LocalDate from, LocalDate to) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", "Test service " + code);
        m.put("classification", "SIMPLE");
        m.put("clientType", "G2C");
        m.put("serviceType", "EXTERNAL");
        m.put("multiStage", false);
        m.put("defaultPrefix", "T");
        m.put("defaultDurationMinutes", 10);
        m.put("active", true);
        m.put("sortOrder", 99);
        m.put("effectiveFrom", from);
        m.put("effectiveTo", to);
        return m;
    }

    @Test
    void effectiveDatesControlAvailabilityAndEveryChangeIsVersioned() throws Exception {
        TestData.SiteFixture site = data.site();
        String national = data.login(mvc, data.user("CO_ADMIN", null, null));
        String lhioAdmin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Manila"));
        String code = TestData.unique("T-");

        // Only national catalog managers may edit the national catalog.
        mvc.perform(post("/api/v1/service-items").header("Authorization", lhioAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(item(code, null, null))))
                .andExpect(status().isForbidden());
        // The effective period must not be empty.
        mvc.perform(post("/api/v1/service-items").header("Authorization", national)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(item(code, today, today))))
                .andExpect(status().isBadRequest());

        // A service that starts tomorrow.
        JsonNode created = data.read(mvc.perform(post("/api/v1/service-items").header("Authorization", national)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(item(code, today.plusDays(1), null))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String itemId = created.get("id").asText();
        JsonNode siteService = data.read(mvc.perform(post("/api/v1/sites/" + site.siteId() + "/services")
                        .header("Authorization", lhioAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("serviceItemId", itemId, "prefix", "T", "active", true,
                                "sortOrder", 5, "estimatedDurationMinutes", 10))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String ssId = siteService.get("id").asText();

        assertThat(kioskOpen(site, ssId)).isFalse();
        issue(site, ssId).andExpect(status().isConflict());

        // Bring it forward to today: now offered. Then retire it from today: no longer offered.
        mvc.perform(put("/api/v1/service-items/" + itemId).header("Authorization", national)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(item(code, today, null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(kioskOpen(site, ssId)).isTrue();
        issue(site, ssId).andExpect(status().isOk());

        mvc.perform(put("/api/v1/service-items/" + itemId).header("Authorization", national)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(item(code, today.minusDays(30), today))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        assertThat(kioskOpen(site, ssId)).isFalse();

        // Full history, newest first, with the author of each version.
        JsonNode history = data.read(mvc.perform(get("/api/v1/service-items/" + itemId + "/history")
                        .header("Authorization", national))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history).extracting(h -> h.get("version").asInt()).containsExactly(3, 2, 1);
        assertThat(history.get(2).get("snapshot").asText()).contains(today.plusDays(1).toString());
        assertThat(history.get(0).get("changedBy").asText()).isNotBlank();
    }

    @Test
    void seededCatalogHasInitialHistory() throws Exception {
        String national = data.login(mvc, data.user("CO_ADMIN", null, null));
        JsonNode items = data.read(mvc.perform(get("/api/v1/service-items").header("Authorization", national))
                .andReturn().getResponse().getContentAsString());
        String first = items.get(0).get("id").asText();
        mvc.perform(get("/api/v1/service-items/" + first + "/history").header("Authorization", national))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(1));
    }

    private boolean kioskOpen(TestData.SiteFixture site, String siteServiceId) throws Exception {
        JsonNode cfg = data.read(mvc.perform(get("/api/v1/kiosk/config").header("X-Device-Key", site.kioskKey()))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode s : cfg.get("services")) {
            if (s.get("siteServiceId").asText().equals(siteServiceId)) {
                return s.get("open").asBoolean();
            }
        }
        throw new AssertionError("service not listed at kiosk");
    }

    private org.springframework.test.web.servlet.ResultActions issue(TestData.SiteFixture site, String ssId)
            throws Exception {
        return mvc.perform(post("/api/v1/kiosk/tickets").header("X-Device-Key", site.kioskKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("siteServiceId", ssId, "channel", "PRINTED", "requestId", UUID.randomUUID()))));
    }

    @Test
    void addAllCatalogServicesFillsTheSiteOnceWithUniquePrefixes() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        String agent = data.login(mvc, data.user("AGENT", null, site.siteId()));
        String path = "/api/v1/sites/" + site.siteId() + "/services/add-catalog";
        mvc.perform(post(path).header("Authorization", agent)).andExpect(status().isForbidden());

        JsonNode added = data.read(mvc.perform(post(path).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Integer wanted = jdbc.queryForObject("""
                select count(*) from service_item
                where active and (effective_to is null or effective_to >= current_date)""", Integer.class);
        // The fixture already offered two of them; the rest were added, each exactly once.
        assertThat(added.size()).isEqualTo(wanted - 2);
        assertThat(jdbc.queryForObject("select count(distinct prefix) from site_service where site_id = ?",
                Integer.class, site.siteId())).isEqualTo(wanted);
        for (JsonNode s : added) {
            assertThat(s.get("prefix").asText()).matches("^[A-Z]{1,3}$");
            assertThat(s.get("active").asBoolean()).isTrue();
        }
        // Nothing left to add the second time.
        mvc.perform(post(path).header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

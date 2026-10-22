package ph.gov.philhealth.qms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Administrators list and end a user's sign-in sessions; ended sessions lose API access immediately. */
@IntegrationTest
class SessionIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    @Test
    void revokedSessionsStopWorkingAtOnce() throws Exception {
        TestData.SiteFixture site = data.site();
        String admin = data.login(mvc, data.user("SUPER_ADMIN", null, null));
        String username = data.user("AGENT", null, site.siteId());
        String phone = data.login(mvc, username);
        String desk = data.login(mvc, username);
        String userId = data.read(mvc.perform(get("/api/v1/auth/me").header("Authorization", desk))
                .andExpect(jsonPath("$.idleTimeoutMinutes").isNumber())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        JsonNode sessions = data.read(mvc.perform(get("/api/v1/users/" + userId + "/sessions").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].current").value(false))
                .andReturn().getResponse().getContentAsString());

        // The agent cannot manage sessions; an admin ends the older one (the phone).
        mvc.perform(get("/api/v1/users/" + userId + "/sessions").header("Authorization", desk))
                .andExpect(status().isForbidden());
        String phoneSession = sessions.get(1).get("id").asText();
        mvc.perform(delete("/api/v1/users/" + userId + "/sessions/" + phoneSession).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", desk)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/users/" + userId + "/sessions").header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", desk)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/" + userId + "/sessions").header("Authorization", admin))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/v1/users/" + userId + "/sessions/" + phoneSession).header("Authorization", admin))
                .andExpect(status().isNotFound());
    }
}

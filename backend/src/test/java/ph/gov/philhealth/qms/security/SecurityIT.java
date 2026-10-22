package ph.gov.philhealth.qms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

@IntegrationTest
class SecurityIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/kiosk/config").header("X-Device-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://qms.example.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://qms.example.test"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void siteScopeIsEnforced() throws Exception {
        var siteA = data.site();
        var siteB = data.site();
        String agentA = data.login(mvc, data.user("AGENT", null, siteA.siteId()));
        mvc.perform(get("/api/v1/sites/" + siteA.siteId() + "/queue").header("Authorization", agentA))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sites/" + siteB.siteId() + "/queue").header("Authorization", agentA))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/agent/session/start").header("Authorization", agentA)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(Map.of("counterId", siteB.counter1()))))
                .andExpect(status().isForbidden());
        // Agents have no user administration rights at all.
        mvc.perform(get("/api/v1/users").header("Authorization", agentA)).andExpect(status().isForbidden());

        // A regional administrator sees sites of their region only.
        String ro = data.login(mvc, data.user("RO_ADMIN", siteA.regionId(), null));
        mvc.perform(get("/api/v1/sites/" + siteA.siteId() + "/queue").header("Authorization", ro)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/sites/" + siteB.siteId() + "/queue").header("Authorization", ro))
                .andExpect(status().isForbidden());
        // Reports cannot be pulled for another region's site.
        mvc.perform(get("/api/v1/reports/summary").header("Authorization", ro).param("from", "2026-01-01")
                        .param("to", "2026-01-31").param("siteId", siteB.siteId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void hierarchicalUserAdministration() throws Exception {
        var site = data.site();
        var other = data.site();
        String lhioAdmin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        Map<String, Object> agent = Map.of("username", TestData.unique("agent").toLowerCase(), "fullName", "New Agent",
                "password", "Another-Pass-77", "assignments", List.of(Map.of("roleCode", "AGENT", "siteId", site.siteId())));
        String created = mvc.perform(post("/api/v1/users").header("Authorization", lhioAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(data.json(agent)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();
        String newId = data.read(created).get("id").asText();

        // Cannot create users at another site, nor grant an equal/higher role.
        mvc.perform(post("/api/v1/users").header("Authorization", lhioAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("username", TestData.unique("x").toLowerCase(), "fullName", "X",
                                "password", "Another-Pass-77",
                                "assignments", List.of(Map.of("roleCode", "AGENT", "siteId", other.siteId()))))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/" + newId + "/assignments").header("Authorization", lhioAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(List.of(Map.of("roleCode", "SUPER_ADMIN")))))
                .andExpect(status().isForbidden());
        // Weak passwords are rejected.
        mvc.perform(post("/api/v1/users").header("Authorization", lhioAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("username", TestData.unique("y").toLowerCase(), "fullName", "Y",
                                "password", "password",
                                "assignments", List.of(Map.of("roleCode", "AGENT", "siteId", site.siteId()))))))
                .andExpect(status().isBadRequest());

        // The new user must change the temporary password before using the system.
        String token = data.read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("username", agent.get("username"), "password", "Another-Pass-77"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/sites/" + site.siteId() + "/queue").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("currentPassword", "Another-Pass-77", "newPassword", "Brand-New-Pass-88"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/sites/" + site.siteId() + "/queue").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Suspension takes effect immediately, even with a still-valid access token.
        mvc.perform(post("/api/v1/users/" + newId + "/status").header("Authorization", lhioAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("status", "SUSPENDED", "reason", "Investigation"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sites/" + site.siteId() + "/queue").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        Integer audits = jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action in "
                + "('USER_CREATED','USER_STATUS_CHANGED','PASSWORD_CHANGED')", Integer.class, newId);
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void listEndpointsWorkWithoutOptionalFilters() throws Exception {
        // Regression: absent string filters used to be bound as untyped NULL, which PostgreSQL rejects.
        var site = data.site();
        String admin = data.login(mvc, data.user("LHIO_ADMIN", null, site.siteId()));
        mvc.perform(get("/api/v1/users").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users").param("q", "user").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/audit").param("from", "2026-01-01").param("to", "2030-12-31")
                        .header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit").param("from", "2026-01-01").param("to", "2030-12-31")
                        .param("action", "LOGIN_SUCCEEDED").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        var site = data.site();
        String username = data.user("AGENT", null, site.siteId());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(data.json(Map.of("username", username, "password", "Wrong-Password-1"))))
                    .andExpect(status().isUnauthorized());
        }
        // Even the correct password is refused while locked.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("username", username, "password", TestData.PASSWORD))))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    private String refresh(String token, int expectedStatus) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("refreshToken", token)))).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return expectedStatus == 200 ? data.read(res).get("refreshToken").asText() : null;
    }

    @Test
    void refreshTokensRotateAndReuseIsDetected() throws Exception {
        var site = data.site();
        String username = data.user("AGENT", null, site.siteId());
        String rt1 = data.read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("username", username, "password", TestData.PASSWORD))))
                .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
        String rt2 = refresh(rt1, 200);
        assertThat(rt2).isNotEqualTo(rt1);

        // Lost response: rt1 is presented again right away while rt2 was never used (e.g. the page
        // reloaded mid-refresh). The client gets a fresh pair; the unused rt2 is cancelled.
        String rt3 = refresh(rt1, 200);
        assertThat(rt3).isNotIn(rt1, rt2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'REFRESH_TOKEN_GRACE_REUSE'"
                + " and actor_username = ?", Integer.class, username)).isEqualTo(1);

        // Presenting the cancelled rt2 is not a lost response: treated as theft, everything is revoked.
        refresh(rt2, 401);
        refresh(rt3, 401);
    }

    @Test
    void reuseOutsideTheGraceWindowRevokesEverything() throws Exception {
        var site = data.site();
        String username = data.user("AGENT", null, site.siteId());
        String rt1 = data.read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("username", username, "password", TestData.PASSWORD))))
                .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
        String rt2 = refresh(rt1, 200);
        // Pretend rt1 was rotated two minutes ago.
        jdbc.update("update refresh_token set revoked_at = now() - interval '2 minutes' where token_hash = ?",
                ph.gov.philhealth.qms.common.Hashing.sha256Hex(rt1));
        refresh(rt1, 401);
        refresh(rt2, 401);   // the whole family is revoked

        // And once the successor has been used, re-presenting the parent is theft even inside the window.
        String rtA = data.read(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(data.json(Map.of("username", username, "password", TestData.PASSWORD))))
                .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
        String rtB = refresh(rtA, 200);
        String rtC = refresh(rtB, 200);
        refresh(rtA, 401);
        refresh(rtC, 401);
    }
}

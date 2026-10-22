package ph.gov.philhealth.qms.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Content approval: second-person rule, with an audited exception for Super Administrators. */
@IntegrationTest
class ContentApprovalIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;

    static UUID pendingContent(JdbcTemplate jdbc, String author) {
        UUID id = Ids.newId();
        jdbc.update("""
                insert into content_item (id, title, type, body, status, created_by, display_seconds)
                values (?, 'Advisory', 'ANNOUNCEMENT', 'Office closes early', 'PENDING_APPROVAL', ?, 10)
                """, id, author);
        return id;
    }

    @Test
    void superAdministratorMayApproveOwnContentAndItIsAudited() throws Exception {
        String superUser = data.user("SUPER_ADMIN", null, null);
        UUID own = pendingContent(jdbc, superUser);
        mvc.perform(post("/api/v1/content/" + own + "/approve").header("Authorization", data.login(mvc, superUser)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.approvedBy").value(superUser));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'CONTENT_SELF_APPROVED' and entity_id = ?",
                Integer.class, own.toString())).isEqualTo(1);
    }

    @Test
    void otherApproversStillNeedASecondPerson() throws Exception {
        String coAdmin = data.user("CO_ADMIN", null, null);
        String token = data.login(mvc, coAdmin);
        UUID own = pendingContent(jdbc, coAdmin);
        mvc.perform(post("/api/v1/content/" + own + "/approve").header("Authorization", token))
                .andExpect(status().isForbidden());

        UUID someoneElses = pendingContent(jdbc, "another.author");
        mvc.perform(post("/api/v1/content/" + someoneElses + "/approve").header("Authorization", token))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'CONTENT_SELF_APPROVED' and entity_id = ?",
                Integer.class, someoneElses.toString())).isZero();
    }
}

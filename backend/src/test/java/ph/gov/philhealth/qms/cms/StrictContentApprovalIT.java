package ph.gov.philhealth.qms.cms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** With QMS_CMS_SUPER_ADMIN_SELF_APPROVAL=false even a Super Administrator needs a second person. */
@IntegrationTest
@TestPropertySource(properties = "qms.cms.super-admin-self-approval=false")
class StrictContentApprovalIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void superAdministratorCannotSelfApproveInStrictMode() throws Exception {
        String superUser = data.user("SUPER_ADMIN", null, null);
        UUID own = ContentApprovalIT.pendingContent(jdbc, superUser);
        mvc.perform(post("/api/v1/content/" + own + "/approve").header("Authorization", data.login(mvc, superUser)))
                .andExpect(status().isForbidden());
    }
}

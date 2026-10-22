package ph.gov.philhealth.qms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ph.gov.philhealth.qms.support.IntegrationTest;

/** Verifies V3: the application role has DML only, and audit/ticket history are append-only. */
@IntegrationTest
class DatabasePrivilegesIT {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private void asAppRole(Runnable r) {
        // SET LOCAL ROLE lasts only for this transaction, which is then rolled back.
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE qms_app_test");
            try {
                r.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void applicationRoleCanReadAndWriteBusinessTables() {
        asAppRole(() -> {
            assertThat(jdbc.queryForObject("select count(*) from service_item", Long.class)).isGreaterThan(0);
            jdbc.update("update service_item set sort_order = sort_order where code = 'SVC-01'");
            jdbc.queryForObject("select count(*) from audit_log", Long.class);
        });
    }

    @Test
    void auditLogAndTicketHistoryAreAppendOnly() {
        assertThatThrownBy(() -> asAppRole(() -> jdbc.update("delete from audit_log")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asAppRole(() -> jdbc.update("update ticket_event set reason = 'x'")))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void applicationRoleCannotChangeSchema() {
        assertThatThrownBy(() -> asAppRole(() -> jdbc.execute("create table hack (id int)")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asAppRole(() -> jdbc.execute("drop table ticket")))
                .rootCause().hasMessageContaining("must be owner");
    }

    @Test
    void applicationRolePurgesHistoryOnlyThroughTheRetentionFunctions() {
        asAppRole(() -> {
            assertThat(jdbc.queryForObject("select qms_purge_audit(now() - interval '100 years', 10)", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select qms_purge_tickets(current_date - 36500, 10)", Integer.class)).isZero();
        });
    }
}

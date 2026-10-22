package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ph.gov.philhealth.qms.common.ApiException;

class TicketStateMachineTest {

    @Test
    void happyPath() {
        assertThat(TicketStateMachine.next(TicketAction.CALL, TicketStatus.WAITING, "A-001")).isEqualTo(TicketStatus.CALLED);
        assertThat(TicketStateMachine.next(TicketAction.RECALL, TicketStatus.CALLED, "A-001")).isEqualTo(TicketStatus.CALLED);
        assertThat(TicketStateMachine.next(TicketAction.HOLD, TicketStatus.CALLED, "A-001")).isEqualTo(TicketStatus.ON_HOLD);
        assertThat(TicketStateMachine.next(TicketAction.RESUME, TicketStatus.ON_HOLD, "A-001")).isEqualTo(TicketStatus.CALLED);
        assertThat(TicketStateMachine.next(TicketAction.TRANSFER, TicketStatus.CALLED, "A-001")).isEqualTo(TicketStatus.WAITING);
        assertThat(TicketStateMachine.next(TicketAction.COMPLETE, TicketStatus.CALLED, "A-001")).isEqualTo(TicketStatus.COMPLETED);
    }

    @Test
    void cannotCompleteWaitingTicket() {
        assertThatThrownBy(() -> TicketStateMachine.next(TicketAction.COMPLETE, TicketStatus.WAITING, "A-001"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("A-001").hasMessageContaining("WAITING")
                .extracting("code").isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void cannotCallTwice() {
        assertThat(TicketStateMachine.isAllowed(TicketAction.CALL, TicketStatus.CALLED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"COMPLETED", "REMOVED", "VOIDED", "EXPIRED"})
    void terminalStatusesAcceptNoAction(TicketStatus terminal) {
        for (TicketAction a : TicketAction.values()) {
            assertThat(TicketStateMachine.isAllowed(a, terminal)).as(a + " from " + terminal).isFalse();
        }
    }
}

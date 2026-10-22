package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TicketAccumulatorTest {

    @Test
    void accumulatesTimePerStatus() {
        Instant t0 = Instant.parse("2026-10-22T00:00:00Z");
        Ticket t = new Ticket();
        t.setStatus(TicketStatus.WAITING);
        t.setStatusChangedAt(t0);

        t.accumulateUntil(t0.plusSeconds(300));           // 5 min waiting
        t.setStatus(TicketStatus.CALLED);
        t.accumulateUntil(t0.plusSeconds(420));           // 2 min at counter
        t.setStatus(TicketStatus.ON_HOLD);
        t.accumulateUntil(t0.plusSeconds(480));           // 1 min on hold
        t.setStatus(TicketStatus.CALLED);
        t.accumulateUntil(t0.plusSeconds(540));           // 1 min at counter

        assertThat(t.getWaitSeconds()).isEqualTo(300);
        assertThat(t.getServiceSeconds()).isEqualTo(180);
        assertThat(t.getHoldSeconds()).isEqualTo(60);
        assertThat(t.getStatusChangedAt()).isEqualTo(t0.plusSeconds(540));
    }

    @Test
    void displayNumberFormat() {
        assertThat(TicketNumberAllocator.format("A", 7)).isEqualTo("A-007");
        assertThat(TicketNumberAllocator.format("AB", 1234)).isEqualTo("AB-1234");
    }
}

package ph.gov.philhealth.qms.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ph.gov.philhealth.qms.sync.ReconciliationService.Status;

class ReconciliationStatusTest {

    @Test
    void classifiesDifferences() {
        assertThat(ReconciliationService.status(10, 10, 40, 40, 2, 2, 0)).isEqualTo(Status.MATCH);
        // Central behind while the site still has unsent entries: expected to catch up.
        assertThat(ReconciliationService.status(10, 8, 40, 31, 2, 2, 12)).isEqualTo(Status.IN_TRANSIT);
        // Central behind with nothing left to send: records were lost or rejected.
        assertThat(ReconciliationService.status(10, 8, 40, 31, 2, 2, 0)).isEqualTo(Status.MISMATCH);
        // Central holds more than the site reports: never explained by a backlog.
        assertThat(ReconciliationService.status(10, 11, 40, 44, 2, 2, 5)).isEqualTo(Status.MISMATCH);
    }
}

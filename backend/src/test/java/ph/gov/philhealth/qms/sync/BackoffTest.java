package ph.gov.philhealth.qms.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void exponentialWithCap() {
        assertThat(SyncPushJob.backoffSeconds(1, 900)).isEqualTo(5);
        assertThat(SyncPushJob.backoffSeconds(2, 900)).isEqualTo(10);
        assertThat(SyncPushJob.backoffSeconds(5, 900)).isEqualTo(80);
        assertThat(SyncPushJob.backoffSeconds(30, 900)).isEqualTo(900);
    }

    @Test
    void unreachableCentralIsRetriedAtLeastEveryMinute() {
        assertThat(SyncPushJob.backoffSeconds(30, SyncPushJob.TRANSPORT_MAX_BACKOFF_SECONDS)).isEqualTo(60);
    }
}

package ph.gov.philhealth.qms.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import ph.gov.philhealth.qms.report.ReportSchedule.Frequency;

class ReportPeriodsTest {

    private static final ZoneId MNL = ZoneId.of("Asia/Manila");

    @Test
    void lastCompletedPeriods() {
        LocalDate thu = LocalDate.of(2026, 10, 22);   // a Thursday
        assertThat(ReportPeriods.lastCompleted(Frequency.DAILY, thu))
                .isEqualTo(new ReportPeriods.Period(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21)));
        assertThat(ReportPeriods.lastCompleted(Frequency.WEEKLY, thu))
                .isEqualTo(new ReportPeriods.Period(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
        assertThat(ReportPeriods.lastCompleted(Frequency.MONTHLY, thu))
                .isEqualTo(new ReportPeriods.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        // On the first day of a month the previous month is complete; leap February handled.
        assertThat(ReportPeriods.lastCompleted(Frequency.MONTHLY, LocalDate.of(2028, 3, 1)))
                .isEqualTo(new ReportPeriods.Period(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)));
        // On a Monday the previous Monday-Sunday week is complete.
        assertThat(ReportPeriods.lastCompleted(Frequency.WEEKLY, LocalDate.of(2026, 10, 19)))
                .isEqualTo(new ReportPeriods.Period(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
    }

    @Test
    void nextRunIsStrictlyInTheFutureAt0100Local() {
        ZonedDateTime thu = ZonedDateTime.of(2026, 10, 22, 14, 0, 0, 0, MNL);
        assertThat(ReportPeriods.nextRunAfter(Frequency.DAILY, thu))
                .isEqualTo(ZonedDateTime.of(2026, 10, 23, 1, 0, 0, 0, MNL));
        assertThat(ReportPeriods.nextRunAfter(Frequency.WEEKLY, thu))
                .isEqualTo(ZonedDateTime.of(2026, 10, 26, 1, 0, 0, 0, MNL));
        assertThat(ReportPeriods.nextRunAfter(Frequency.MONTHLY, thu))
                .isEqualTo(ZonedDateTime.of(2026, 11, 1, 1, 0, 0, 0, MNL));
        // Exactly at the run time, the next run is the following one (no double run).
        ZonedDateTime at = ZonedDateTime.of(2026, 11, 1, 1, 0, 0, 0, MNL);
        assertThat(ReportPeriods.nextRunAfter(Frequency.MONTHLY, at))
                .isEqualTo(ZonedDateTime.of(2026, 12, 1, 1, 0, 0, 0, MNL));
        assertThat(ReportPeriods.nextRunAfter(Frequency.DAILY, at.minusMinutes(1))).isEqualTo(at);
    }
}

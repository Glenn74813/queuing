package ph.gov.philhealth.qms.report;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import ph.gov.philhealth.qms.report.ReportSchedule.Frequency;

/** Calendar rules for scheduled reports, in the reporting time zone. Weeks run Monday to Sunday. */
public final class ReportPeriods {

    /** Runs start at 01:00 local time, after the day's tickets have been closed by the expiry job. */
    public static final LocalTime RUN_TIME = LocalTime.of(1, 0);

    public record Period(LocalDate from, LocalDate to) {
    }

    private ReportPeriods() {
    }

    /** The most recent period that has fully ended before {@code today}. */
    public static Period lastCompleted(Frequency f, LocalDate today) {
        return switch (f) {
            case DAILY -> new Period(today.minusDays(1), today.minusDays(1));
            case WEEKLY -> {
                LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Period(thisMonday.minusWeeks(1), thisMonday.minusDays(1));
            }
            case MONTHLY -> {
                LocalDate firstOfThisMonth = today.withDayOfMonth(1);
                yield new Period(firstOfThisMonth.minusMonths(1), firstOfThisMonth.minusDays(1));
            }
        };
    }

    /** The next run time strictly after {@code now}: 01:00 on the day after the next period ends. */
    public static ZonedDateTime nextRunAfter(Frequency f, ZonedDateTime now) {
        LocalDate d = now.toLocalDate();
        ZonedDateTime candidate = switch (f) {
            case DAILY -> d.atTime(RUN_TIME).atZone(now.getZone());
            case WEEKLY -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atTime(RUN_TIME)
                    .atZone(now.getZone());
            case MONTHLY -> d.withDayOfMonth(1).atTime(RUN_TIME).atZone(now.getZone());
        };
        while (!candidate.isAfter(now)) {
            candidate = switch (f) {
                case DAILY -> candidate.plusDays(1);
                case WEEKLY -> candidate.plusWeeks(1);
                case MONTHLY -> candidate.plusMonths(1);
            };
        }
        return candidate;
    }
}

package ph.gov.philhealth.qms.queue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next number for (site, prefix, numbering period) with a single atomic upsert.
 * The row lock is held until the issuing transaction commits, so concurrent kiosks never receive
 * the same number, and a rolled-back issue does not leave a gap visible to other transactions.
 * The period is keyed by its first day, so numbering restarts when the site's reset schedule says.
 * A number is never lower than one already issued today for the prefix, so changing the schedule
 * in the middle of a day cannot repeat a number on the same day.
 */
@Component
public class TicketNumberAllocator {

    private final JdbcTemplate jdbc;

    public TicketNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Reset { DAILY, WEEKLY, MONTHLY, YEARLY }

    @Transactional(propagation = Propagation.MANDATORY)
    public int next(UUID siteId, String prefix, LocalDate businessDate, Reset reset) {
        Integer value = jdbc.queryForObject("""
                with today as (
                    select coalesce(max(seq_no), 0) as top from ticket
                    where site_id = ? and business_date = ? and prefix = ?)
                insert into ticket_sequence (site_id, prefix, business_date, last_value)
                select ?, ?, ?, today.top + 1 from today
                on conflict (site_id, prefix, business_date)
                do update set last_value = greatest(ticket_sequence.last_value, excluded.last_value - 1) + 1
                returning last_value
                """, Integer.class, siteId, businessDate, prefix, siteId, prefix, periodStart(businessDate, reset));
        if (value == null) {
            throw new IllegalStateException("Ticket sequence allocation failed");
        }
        return value;
    }

    /** First business date of the numbering period that contains {@code date}. Weeks start on Monday. */
    public static LocalDate periodStart(LocalDate date, Reset reset) {
        return switch (reset) {
            case DAILY -> date;
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
            case YEARLY -> date.withDayOfYear(1);
        };
    }

    /** Default site format: prefix, hyphen, three digits (A-007). */
    public static String format(String prefix, int seq) {
        return format(prefix, seq, "-", 3);
    }

    /** Zero-padded to {@code digits}; a number that outgrows the width keeps all its digits. */
    public static String format(String prefix, int seq, String separator, int digits) {
        return prefix + separator + String.format("%0" + digits + "d", seq);
    }
}

package ph.gov.philhealth.qms.queue;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next number for (site, prefix, business date) with a single atomic upsert.
 * The row lock is held until the issuing transaction commits, so concurrent kiosks never receive
 * the same number, and a rolled-back issue does not leave a gap visible to other transactions.
 * Numbering resets daily because the business date is part of the key.
 */
@Component
public class TicketNumberAllocator {

    private final JdbcTemplate jdbc;

    public TicketNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int next(UUID siteId, String prefix, LocalDate businessDate) {
        Integer value = jdbc.queryForObject("""
                insert into ticket_sequence (site_id, prefix, business_date, last_value) values (?, ?, ?, 1)
                on conflict (site_id, prefix, business_date)
                do update set last_value = ticket_sequence.last_value + 1
                returning last_value
                """, Integer.class, siteId, prefix, businessDate);
        if (value == null) {
            throw new IllegalStateException("Ticket sequence allocation failed");
        }
        return value;
    }

    public static String format(String prefix, int seq) {
        return "%s-%03d".formatted(prefix, seq);
    }
}

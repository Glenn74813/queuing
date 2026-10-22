package ph.gov.philhealth.qms.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** Single source of "now" so business-date logic is consistent and testable. */
@Component
public class TimeService {

    private final Clock clock;

    public TimeService() {
        this(Clock.systemUTC());
    }

    public TimeService(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate businessDate(String timezone) {
        return LocalDate.ofInstant(now(), ZoneId.of(timezone));
    }

    public LocalTime localTime(String timezone) {
        return LocalTime.ofInstant(now(), ZoneId.of(timezone));
    }
}

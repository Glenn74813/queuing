package ph.gov.philhealth.qms.counter;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

/** One opening window of a counter on a weekday (ISO day: 1 = Monday … 7 = Sunday). */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CounterHours {

    @Column(name = "day_of_week")
    private int dayOfWeek;
    @Column(name = "open_time")
    @JdbcType(LocalTimeJdbcType.class)   // wall-clock time: no JVM/UTC time-zone shift
    private LocalTime openTime;
    @Column(name = "close_time")
    @JdbcType(LocalTimeJdbcType.class)
    private LocalTime closeTime;

    boolean covers(int day, LocalTime time) {
        return dayOfWeek == day && !time.isBefore(openTime) && time.isBefore(closeTime);
    }
}

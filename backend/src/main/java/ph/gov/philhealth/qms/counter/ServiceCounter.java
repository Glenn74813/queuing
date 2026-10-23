package ph.gov.philhealth.qms.counter;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

@Entity
@Table(name = "service_counter")
@Getter
@Setter
public class ServiceCounter extends BaseEntity {

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    private String code;
    private String name;
    private boolean active = true;

    /** Site services this counter is eligible to serve. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "counter_service", joinColumns = @JoinColumn(name = "counter_id"))
    @Column(name = "site_service_id")
    private Set<UUID> siteServiceIds = new HashSet<>();

    /** Weekly opening windows; empty = no schedule (always available while its services are open). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "counter_hours", joinColumns = @JoinColumn(name = "counter_id"))
    private Set<CounterHours> hours = new HashSet<>();

    public enum ScheduleState { NO_SCHEDULE, OPEN, CLOSED }

    public ScheduleState scheduleState(LocalDate date, LocalTime time) {
        if (hours.isEmpty()) {
            return ScheduleState.NO_SCHEDULE;
        }
        int day = date.getDayOfWeek().getValue();
        return hours.stream().anyMatch(h -> h.covers(day, time)) ? ScheduleState.OPEN : ScheduleState.CLOSED;
    }
}

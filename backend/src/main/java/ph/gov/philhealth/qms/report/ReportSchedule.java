package ph.gov.philhealth.qms.report;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ph.gov.philhealth.qms.common.BaseEntity;

/** A summary report generated automatically for each completed day, week or month. */
@Entity
@Table(name = "report_schedule")
@Getter
@Setter
public class ReportSchedule extends BaseEntity {

    public enum Frequency { DAILY, WEEKLY, MONTHLY }

    public enum Format { CSV, XLSX, PDF }

    private String name;

    @Enumerated(EnumType.STRING)
    private Frequency frequency;

    @Enumerated(EnumType.STRING)
    private ReportDtos.GroupBy groupBy;

    @Enumerated(EnumType.STRING)
    private Format format;

    private UUID siteId;
    private UUID regionId;
    private boolean active = true;
    private String createdBy;
    private Instant nextRunAt;
    private Instant lastRunAt;
}

package ph.gov.philhealth.qms.report;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One generated (or failed) file of a {@link ReportSchedule}. */
@Entity
@Table(name = "report_run")
@Getter
@Setter
public class ReportRun {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Status { SUCCESS, FAILED }

    @Id
    private UUID id;
    private UUID scheduleId;
    private LocalDate periodFrom;
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String fileName;
    private Long sizeBytes;
    private String sha256;
    private String error;
    private Instant generatedAt;
}

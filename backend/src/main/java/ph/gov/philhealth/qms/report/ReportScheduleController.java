package ph.gov.philhealth.qms.report;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.report.ReportScheduleService.RunDto;
import ph.gov.philhealth.qms.report.ReportScheduleService.ScheduleDto;
import ph.gov.philhealth.qms.report.ReportScheduleService.ScheduleInput;

@RestController
public class ReportScheduleController {

    private final ReportScheduleService service;

    public ReportScheduleController(ReportScheduleService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/report-schedules")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<ScheduleDto> list() {
        return service.list();
    }

    @PostMapping("/api/v1/report-schedules")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ScheduleDto create(@Valid @RequestBody ScheduleInput in) {
        return service.create(in);
    }

    @PutMapping("/api/v1/report-schedules/{id}")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ScheduleDto update(@PathVariable UUID id, @Valid @RequestBody ScheduleInput in) {
        return service.update(id, in);
    }

    @PostMapping("/api/v1/report-schedules/{id}/run-now")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public RunDto runNow(@PathVariable UUID id) {
        return service.runNow(id);
    }

    @GetMapping("/api/v1/report-schedules/{id}/runs")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<RunDto> runs(@PathVariable UUID id) {
        return service.runs(id);
    }

    @GetMapping("/api/v1/report-runs/{id}/download")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        ReportScheduleService.Download d = service.download(id);
        MediaType type = switch (d.format()) {
            case CSV -> new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
            case XLSX -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case PDF -> MediaType.APPLICATION_PDF;
        };
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName())
                        .build().toString())
                .body(new FileSystemResource(d.path()));
    }
}

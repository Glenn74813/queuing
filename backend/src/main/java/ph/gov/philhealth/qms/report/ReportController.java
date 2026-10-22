package ph.gov.philhealth.qms.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.report.ReportDtos.ExceptionRow;
import ph.gov.philhealth.qms.report.ReportDtos.Filter;
import ph.gov.philhealth.qms.report.ReportDtos.GroupBy;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryReport;
import ph.gov.philhealth.qms.report.ReportDtos.TicketReportRow;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reports;
    private final ReportExporter exporter;
    private final AuditService audit;
    private final AgentWorkbookExporter workbookExporter;
    private final ph.gov.philhealth.qms.sla.SlaService slaService;

    public ReportController(ReportService reports, ReportExporter exporter, AuditService audit,
                            AgentWorkbookExporter workbookExporter, ph.gov.philhealth.qms.sla.SlaService slaService) {
        this.reports = reports;
        this.exporter = exporter;
        this.audit = audit;
        this.workbookExporter = workbookExporter;
        this.slaService = slaService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @Transactional
    public SummaryReport summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) UUID regionId,
                                 @RequestParam(required = false) UUID siteId,
                                 @RequestParam(required = false) String clientType,
                                 @RequestParam(required = false) String serviceType,
                                 @RequestParam(required = false) String classification,
                                 @RequestParam(required = false) String channel,
                                 @RequestParam(defaultValue = "SERVICE") GroupBy groupBy) {
        Filter f = new Filter(from, to, regionId, siteId, clientType, serviceType, classification, channel);
        SummaryReport r = reports.summary(f, groupBy);
        audit.record("REPORT_GENERATED", "Report", "summary", siteId, f + " groupBy=" + groupBy);
        return r;
    }

    @GetMapping("/summary/export")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @Transactional
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID regionId,
                                         @RequestParam(required = false) UUID siteId,
                                         @RequestParam(required = false) String clientType,
                                         @RequestParam(required = false) String serviceType,
                                         @RequestParam(required = false) String classification,
                                         @RequestParam(required = false) String channel,
                                         @RequestParam(defaultValue = "SERVICE") GroupBy groupBy,
                                         @RequestParam(defaultValue = "csv") String format) {
        Filter f = new Filter(from, to, regionId, siteId, clientType, serviceType, classification, channel);
        SummaryReport r = reports.summary(f, groupBy);
        byte[] body;
        MediaType type;
        switch (format) {
            case "csv" -> {
                body = exporter.csv(r);
                type = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
            }
            case "xlsx" -> {
                body = exporter.xlsx(r);
                type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "pdf" -> {
                body = exporter.pdf(r);
                type = MediaType.APPLICATION_PDF;
            }
            default -> throw ApiException.badRequest("Supported formats: csv, xlsx, pdf");
        }
        audit.record("REPORT_EXPORTED", "Report", "summary", siteId, format + " " + f + " groupBy=" + groupBy);
        String filename = "qms-summary-%s_%s.%s".formatted(from, to, format);
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public PageResponse<TicketReportRow> tickets(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) UUID regionId,
                                                 @RequestParam(required = false) UUID siteId,
                                                 @RequestParam(required = false) String clientType,
                                                 @RequestParam(required = false) String serviceType,
                                                 @RequestParam(required = false) String classification,
                                                 @RequestParam(required = false) String channel,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return reports.tickets(new Filter(from, to, regionId, siteId, clientType, serviceType, classification, channel),
                page, size);
    }

    @GetMapping("/tickets/export")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @Transactional
    public ResponseEntity<byte[]> exportTickets(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(required = false) UUID regionId,
                                                @RequestParam(required = false) UUID siteId,
                                                @RequestParam(required = false) String clientType,
                                                @RequestParam(required = false) String serviceType,
                                                @RequestParam(required = false) String classification,
                                                @RequestParam(required = false) String channel) {
        Filter f = new Filter(from, to, regionId, siteId, clientType, serviceType, classification, channel);
        var rows = reports.ticketsForExport(f);
        audit.record("REPORT_EXPORTED", "Report", "tickets", siteId, "csv rows=" + rows.size() + " " + f);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("qms-tickets-%s_%s.csv".formatted(from, to)).build().toString())
                .body(exporter.ticketsCsv(rows));
    }

    @GetMapping("/agent-performance")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @Transactional
    public ReportDtos.AgentPerformanceReport agentPerformance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID regionId, @RequestParam(required = false) UUID siteId) {
        Filter f = new Filter(from, to, regionId, siteId, null, null, null, null);
        var report = reports.agentPerformance(f);
        audit.record("REPORT_GENERATED", "Report", "agent-performance", siteId, f.toString());
        return report;
    }

    @GetMapping("/agent-performance/export")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @Transactional
    public ResponseEntity<byte[]> exportAgentPerformance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID regionId, @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "csv") String format) {
        if (!List.of("csv", "xlsx", "pdf").contains(format)) {
            throw ApiException.badRequest("Supported formats: csv, xlsx, pdf");
        }
        Filter f = new Filter(from, to, regionId, siteId, null, null, null, null);
        if (format.equals("xlsx")) {
            // Excel: the full multi-sheet workbook of every agent (summary, daily, transactions, targets).
            byte[] body = workbookExporter.export(reports.agentWorkbook(f), slaService.list());
            audit.record("REPORT_EXPORTED", "Report", "agent-performance", siteId, "xlsx workbook " + f);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("qms-agent-performance-%s_%s.xlsx".formatted(from, to)).build().toString())
                    .body(body);
        }
        var report = reports.agentPerformance(f);
        List<String> headers = List.of("Agent", "Username", "Site", "Region", "Handled", "Completed", "Transferred",
                "No-shows", "Recalls", "Avg processing (min)", "Processing within target (%)", "Avg wait (min)",
                "Waiting within target (%)", "Waiting target (min)", "Processing target (min)", "Compliance target (%)",
                "Service overrides", "SLA");
        List<List<String>> rows = report.rows().stream().map(r -> java.util.Arrays.asList(r.agentName(), r.username(),
                r.siteName(), r.regionName(), str(r.handled()), str(r.completed()), str(r.transferred()),
                str(r.noShows()), str(r.recalls()), str(r.avgProcessingMinutes()), str(r.processingWithinTargetPercent()),
                str(r.avgWaitMinutes()), str(r.waitingWithinTargetPercent()), str(r.waitingTargetMinutes()),
                str(r.processingTargetMinutes()), str(r.complianceTargetPercent()), r.serviceOverrides() ? "Yes" : "No",
                r.slaStatus().name().replace('_', ' '))).toList();
        byte[] body = exporter.table(format, "PhilHealth QMS - Agent performance against SLA",
                "Period %s to %s. Generated %s UTC. Targets per region (service overrides apply where set); SLA met when processing within target >= compliance target."
                        .formatted(from, to, report.generatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
                headers, rows);
        audit.record("REPORT_EXPORTED", "Report", "agent-performance", siteId, format + " " + f);
        MediaType type = switch (format) {
            case "csv" -> new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.APPLICATION_PDF;
        };
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("qms-agent-performance-%s_%s.%s".formatted(from, to, format)).build().toString())
                .body(body);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<ExceptionRow> exceptions(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID regionId,
                                         @RequestParam(required = false) UUID siteId) {
        return reports.exceptions(new Filter(from, to, regionId, siteId, null, null, null, null));
    }
}

package ph.gov.philhealth.qms.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.config.QmsProperties;
import ph.gov.philhealth.qms.org.Region;
import ph.gov.philhealth.qms.org.RegionRepository;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.report.ReportDtos.Filter;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryReport;
import ph.gov.philhealth.qms.report.ReportPeriods.Period;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/**
 * Scheduled summary reports. Authorization happens at the two points where a user is involved:
 * defining the schedule (REPORT_EXPORT for its site/region/national target) and downloading a file
 * (REPORT_EXPORT for that target again). Generation itself runs as the system.
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);

    public record ScheduleDto(UUID id, String name, ReportSchedule.Frequency frequency, ReportDtos.GroupBy groupBy,
                              ReportSchedule.Format format, UUID siteId, String siteName, UUID regionId,
                              String regionName, boolean active, String createdBy, Instant nextRunAt,
                              Instant lastRunAt, ReportRun.Status lastRunStatus) {
    }

    public record ScheduleInput(@jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 120) String name,
                                @jakarta.validation.constraints.NotNull ReportSchedule.Frequency frequency,
                                @jakarta.validation.constraints.NotNull ReportDtos.GroupBy groupBy,
                                @jakarta.validation.constraints.NotNull ReportSchedule.Format format,
                                UUID siteId, UUID regionId, boolean active) {
    }

    public record RunDto(UUID id, UUID scheduleId, LocalDate periodFrom, LocalDate periodTo,
                         ReportRun.Trigger trigger, ReportRun.Status status, String fileName, Long sizeBytes,
                         String sha256, String error, Instant generatedAt) {
        static RunDto of(ReportRun r) {
            return new RunDto(r.getId(), r.getScheduleId(), r.getPeriodFrom(), r.getPeriodTo(), r.getTrigger(),
                    r.getStatus(), r.getFileName(), r.getSizeBytes(), r.getSha256(), r.getError(), r.getGeneratedAt());
        }
    }

    /** A generated file ready to stream to the client. */
    public record Download(Path path, String fileName, ReportSchedule.Format format) {
    }

    private final ReportScheduleRepository schedules;
    private final ReportRunRepository runs;
    private final ReportService reports;
    private final ReportExporter exporter;
    private final SiteRepository sites;
    private final RegionRepository regions;
    private final ScopeService scope;
    private final AuditService audit;
    private final TimeService time;
    private final TransactionTemplate tx;
    private final ph.gov.philhealth.qms.monitoring.QmsMetrics metrics;
    private final ZoneId zone;
    private final Path dir;
    private final int retentionDays;

    public ReportScheduleService(ReportScheduleRepository schedules, ReportRunRepository runs, ReportService reports,
                                 ReportExporter exporter, SiteRepository sites, RegionRepository regions,
                                 ScopeService scope, AuditService audit, TimeService time, TransactionTemplate tx,
                                 QmsProperties props, ph.gov.philhealth.qms.monitoring.QmsMetrics metrics)
            throws IOException {
        this.schedules = schedules;
        this.runs = runs;
        this.reports = reports;
        this.exporter = exporter;
        this.sites = sites;
        this.regions = regions;
        this.scope = scope;
        this.audit = audit;
        this.time = time;
        this.tx = tx;
        this.metrics = metrics;
        this.zone = ZoneId.of(props.node().defaultTimezone());
        this.dir = Path.of(props.storage().reportsDir()).toAbsolutePath().normalize();
        this.retentionDays = Math.max(1, props.storage().reportsRetentionDays());
        Files.createDirectories(dir);
    }

    // ------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public List<ScheduleDto> list() {
        AuthUser user = CurrentUser.get();
        return schedules.findAllByOrderByNameAsc().stream().filter(s -> visible(user, s)).map(this::toDto).toList();
    }

    @Transactional
    public ScheduleDto create(ScheduleInput in) {
        ReportSchedule s = new ReportSchedule();
        apply(s, in);
        s.setCreatedBy(CurrentUser.get().username());
        s.setNextRunAt(ReportPeriods.nextRunAfter(s.getFrequency(), ZonedDateTime.ofInstant(time.now(), zone))
                .toInstant());
        schedules.save(s);
        audit.record("REPORT_SCHEDULE_CREATED", "ReportSchedule", s.getId(), s.getSiteId(), describe(s));
        return toDto(s);
    }

    @Transactional
    public ScheduleDto update(UUID id, ScheduleInput in) {
        ReportSchedule s = find(id);
        requireTarget(s.getSiteId(), s.getRegionId());
        ReportSchedule.Frequency before = s.getFrequency();
        apply(s, in);
        if (before != s.getFrequency()) {
            s.setNextRunAt(ReportPeriods.nextRunAfter(s.getFrequency(), ZonedDateTime.ofInstant(time.now(), zone))
                    .toInstant());
        }
        audit.record("REPORT_SCHEDULE_UPDATED", "ReportSchedule", s.getId(), s.getSiteId(), describe(s));
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public List<RunDto> runs(UUID scheduleId) {
        ReportSchedule s = find(scheduleId);
        if (!visible(CurrentUser.get(), s)) {
            throw ApiException.forbidden("Outside your reporting scope");
        }
        return runs.findTop50ByScheduleIdOrderByGeneratedAtDesc(scheduleId).stream().map(RunDto::of).toList();
    }

    /** Generates the last completed period now (e.g. to test a new schedule). */
    public RunDto runNow(UUID scheduleId) {
        ReportSchedule s = tx.execute(st -> {
            ReportSchedule found = find(scheduleId);
            requireTarget(found.getSiteId(), found.getRegionId());
            return found;
        });
        ReportRun run = generate(s, ReportRun.Trigger.MANUAL);
        tx.executeWithoutResult(st -> audit.record("REPORT_SCHEDULE_RUN", "ReportSchedule", s.getId(), s.getSiteId(),
                run.getStatus() + " " + run.getPeriodFrom() + ".." + run.getPeriodTo()));
        return RunDto.of(run);
    }

    @Transactional
    public Download download(UUID runId) {
        ReportRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Report run"));
        ReportSchedule s = find(run.getScheduleId());
        requireTarget(s.getSiteId(), s.getRegionId());
        if (run.getStatus() != ReportRun.Status.SUCCESS || run.getFileName() == null) {
            throw ApiException.notFound("Report file");
        }
        Path path = dir.resolve(run.getFileName()).normalize();
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw ApiException.notFound("Report file (it may have passed the retention period)");
        }
        audit.record("REPORT_DOWNLOADED", "ReportRun", run.getId(), s.getSiteId(), s.getName() + " "
                + run.getPeriodFrom() + ".." + run.getPeriodTo());
        String name = "%s_%s_%s.%s".formatted(s.getName().replaceAll("[^A-Za-z0-9-]+", "-"), run.getPeriodFrom(),
                run.getPeriodTo(), s.getFormat().name().toLowerCase());
        return new Download(path, name, s.getFormat());
    }

    // ------------------------------------------------------------------ generation

    /** Every five minutes: generate all due schedules, then remove files past the retention period. */
    @Scheduled(fixedDelayString = "${qms.reports.poll-ms:300000}", initialDelay = 60_000)
    public void runDue() {
        try {
            List<UUID> due = tx.execute(st -> {
                Instant now = time.now();
                List<ReportSchedule> locked = schedules.lockDue(now);
                // Claim them by moving next_run_at forward inside the lock; generation happens after.
                locked.forEach(s -> {
                    s.setNextRunAt(ReportPeriods.nextRunAfter(s.getFrequency(), ZonedDateTime.ofInstant(now, zone))
                            .toInstant());
                    s.setLastRunAt(now);
                });
                return locked.stream().map(ReportSchedule::getId).toList();
            });
            for (UUID id : due) {
                ReportSchedule s = tx.execute(st -> find(id));
                ReportRun run = generate(s, ReportRun.Trigger.SCHEDULED);
                log.info("Scheduled report '{}' {}..{}: {}", s.getName(), run.getPeriodFrom(), run.getPeriodTo(),
                        run.getStatus());
            }
            purgeExpired();
        } catch (RuntimeException e) {
            log.error("Scheduled report processing failed", e);
        }
    }

    ReportRun generate(ReportSchedule s, ReportRun.Trigger trigger) {
        Period p = ReportPeriods.lastCompleted(s.getFrequency(), LocalDate.ofInstant(time.now(), zone));
        ReportRun run = new ReportRun();
        run.setId(Ids.newId());
        run.setScheduleId(s.getId());
        run.setPeriodFrom(p.from());
        run.setPeriodTo(p.to());
        run.setTrigger(trigger);
        try {
            SummaryReport report = reports.summaryForSchedule(
                    new Filter(p.from(), p.to(), s.getRegionId(), s.getSiteId(), null, null, null, null), s.getGroupBy());
            byte[] bytes = switch (s.getFormat()) {
                case CSV -> exporter.csv(report);
                case XLSX -> exporter.xlsx(report);
                case PDF -> exporter.pdf(report);
            };
            String fileName = run.getId() + "." + s.getFormat().name().toLowerCase();
            Files.write(dir.resolve(fileName), bytes);
            run.setFileName(fileName);
            run.setSizeBytes((long) bytes.length);
            run.setSha256(Hashing.sha256Hex(bytes));
            run.setStatus(ReportRun.Status.SUCCESS);
        } catch (IOException | RuntimeException e) {
            log.warn("Report generation for schedule {} failed: {}", s.getId(), e.getMessage());
            run.setStatus(ReportRun.Status.FAILED);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run.setError(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
        }
        run.setGeneratedAt(time.now());
        tx.executeWithoutResult(st -> runs.save(run));
        metrics.reportRun(run.getStatus().name());
        return run;
    }

    void purgeExpired() {
        Instant cutoff = time.now().minus(retentionDays, ChronoUnit.DAYS);
        tx.executeWithoutResult(st -> runs.findByGeneratedAtBeforeAndFileNameIsNotNull(cutoff).forEach(r -> {
            try {
                Files.deleteIfExists(dir.resolve(r.getFileName()));
            } catch (IOException e) {
                log.warn("Could not delete expired report {}: {}", r.getFileName(), e.getMessage());
                return;
            }
            r.setFileName(null);
        }));
    }

    // ------------------------------------------------------------------ helpers

    private void apply(ReportSchedule s, ScheduleInput in) {
        if (in.siteId() != null && in.regionId() != null) {
            throw ApiException.badRequest("Choose a site or a region, not both");
        }
        requireTarget(in.siteId(), in.regionId());
        s.setName(in.name().trim());
        s.setFrequency(in.frequency());
        s.setGroupBy(in.groupBy());
        s.setFormat(in.format());
        s.setSiteId(in.siteId());
        s.setRegionId(in.regionId());
        s.setActive(in.active());
    }

    private void requireTarget(UUID siteId, UUID regionId) {
        if (siteId != null) {
            scope.requireSite(Permission.REPORT_EXPORT, siteId);
        } else if (regionId != null) {
            scope.requireRegion(Permission.REPORT_EXPORT, regionId);
        } else {
            scope.requireNational(Permission.REPORT_EXPORT);
        }
    }

    private boolean visible(AuthUser user, ReportSchedule s) {
        if (s.getSiteId() != null) {
            Site site = sites.findById(s.getSiteId()).orElse(null);
            return site != null && user.canAccessSite(Permission.REPORT_VIEW, site.getId(), site.getRegionId());
        }
        if (s.getRegionId() != null) {
            return user.grants().stream().anyMatch(g -> g.permissions().contains(Permission.REPORT_VIEW)
                    && (g.regionId() == null && g.siteId() == null || s.getRegionId().equals(g.regionId())));
        }
        return user.hasNational(Permission.REPORT_VIEW);
    }

    private ReportSchedule find(UUID id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.notFound("Report schedule"));
    }

    private ScheduleDto toDto(ReportSchedule s) {
        String siteName = s.getSiteId() == null ? null : sites.findById(s.getSiteId()).map(Site::getName).orElse(null);
        String regionName = s.getRegionId() == null ? null
                : regions.findById(s.getRegionId()).map(Region::getName).orElse(null);
        ReportRun.Status last = runs.findFirstByScheduleIdOrderByGeneratedAtDesc(s.getId())
                .map(ReportRun::getStatus).orElse(null);
        return new ScheduleDto(s.getId(), s.getName(), s.getFrequency(), s.getGroupBy(), s.getFormat(), s.getSiteId(),
                siteName, s.getRegionId(), regionName, s.isActive(), s.getCreatedBy(), s.getNextRunAt(),
                s.getLastRunAt(), last);
    }

    private static String describe(ReportSchedule s) {
        return "%s %s by %s as %s site=%s region=%s active=%s".formatted(s.getName(), s.getFrequency(),
                s.getGroupBy(), s.getFormat(), s.getSiteId(), s.getRegionId(), s.isActive());
    }

}

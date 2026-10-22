package ph.gov.philhealth.qms.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportDtos {

    /** Version of docs/metrics-definitions.md implemented by {@link ReportService}. */
    public static final String METRIC_DEFINITIONS_VERSION = "1.0-draft";

    private ReportDtos() {
    }

    public enum GroupBy { SITE, REGION, AREA, SERVICE, CLIENT_TYPE, SERVICE_TYPE, CLASSIFICATION, MULTI_STAGE, CHANNEL,
        DEVICE, STATUS, DAY }

    public record Filter(LocalDate from, LocalDate to, UUID regionId, UUID siteId, String clientType,
                         String serviceType, String classification, String channel) {
    }

    public record SummaryRow(String key, String label, long issued, long served, long abandoned, long voided,
                             long rerouted, Double avgQueueMinutes, Double avgWaitMinutes, Double avgProcessMinutes,
                             long feedbackCount, Double avgRating) {
    }

    public record SummaryReport(LocalDate from, LocalDate to, GroupBy groupBy, List<SummaryRow> rows,
                                SummaryRow totals, Instant generatedAt, String metricDefinitionsVersion) {
    }

    public record TicketReportRow(UUID id, String displayNumber, LocalDate businessDate, UUID siteId, String siteName,
                                  String regionName, String serviceName, String clientType, String serviceType,
                                  String classification, String priorityCode, String channel, String status,
                                  int stageNo, String counterName, String agentName, Instant issuedAt,
                                  Instant firstCalledAt, Instant completedAt, Long queueSeconds, long waitSeconds,
                                  long serviceSeconds, long holdSeconds, int transferCount, int recallCount,
                                  String deviceCode) {
    }

    public enum SlaStatus { MET, NOT_MET, NO_DATA }

    /** One agent at one site over the period, measured against the region's SLA (see metrics-definitions.md). */
    public record AgentPerformanceRow(UUID agentId, String agentName, String username, UUID siteId, String siteName,
                                      String regionName, long handled, long completed, long transferred,
                                      long noShows, long recalls, Double avgProcessingMinutes,
                                      Double processingWithinTargetPercent, Double avgWaitMinutes,
                                      Double waitingWithinTargetPercent, Integer waitingTargetMinutes,
                                      Integer processingTargetMinutes, Double complianceTargetPercent,
                                      boolean serviceOverrides, SlaStatus slaStatus) {
    }

    public record AgentDailyRow(LocalDate date, AgentPerformanceRow metrics) {
    }

    /** One client handled by one agent at one service stage, with the targets that applied. */
    public record AgentTransactionRow(LocalDate businessDate, String ticketNumber, String agentName, String username,
                                      String siteName, String serviceName, Double waitMinutes,
                                      Integer waitTargetMinutes, Boolean waitWithinTarget, Double processingMinutes,
                                      Integer processingTargetMinutes, Boolean processingWithinTarget,
                                      String outcome) {
    }

    public record AgentWorkbook(LocalDate from, LocalDate to, List<AgentPerformanceRow> agents,
                                List<AgentDailyRow> daily, List<AgentTransactionRow> transactions,
                                Instant generatedAt) {
    }

    public record AgentPerformanceReport(LocalDate from, LocalDate to, List<AgentPerformanceRow> rows,
                                         Instant generatedAt) {
    }

    public record ExceptionRow(UUID ticketId, String displayNumber, String siteName, LocalDate businessDate,
                               String issue) {
    }
}

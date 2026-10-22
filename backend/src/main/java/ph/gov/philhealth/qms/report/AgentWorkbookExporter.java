package ph.gov.philhealth.qms.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.report.ReportDtos.AgentDailyRow;
import ph.gov.philhealth.qms.report.ReportDtos.AgentPerformanceRow;
import ph.gov.philhealth.qms.report.ReportDtos.AgentTransactionRow;
import ph.gov.philhealth.qms.report.ReportDtos.AgentWorkbook;
import ph.gov.philhealth.qms.sla.SlaService.SlaPolicyDto;

/**
 * Formatted Excel workbook of every agent's performance: Summary, Daily breakdown, Transactions,
 * SLA targets and Definitions sheets, each with a frozen bold header and filters.
 */
@Component
public class AgentWorkbookExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Manila"));

    private static final String[] AGENT_HEADERS = {"Agent", "Username", "Site", "Region", "Handled", "Completed",
            "Transferred", "No-shows", "Recalls", "Avg processing (min)", "Processing within target",
            "Avg wait (min)", "Waiting within target", "Waiting target (min)", "Processing target (min)",
            "Compliance target", "Service overrides", "SLA"};

    /** Cell styles of one workbook. */
    private record Styles(CellStyle title, CellStyle subtitle, CellStyle header, CellStyle text, CellStyle integer,
                          CellStyle decimal, CellStyle percent, CellStyle met, CellStyle notMet, CellStyle noData,
                          CellStyle wrap) {
    }

    public byte[] export(AgentWorkbook data, List<SlaPolicyDto> policies) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = styles(wb);
            String period = "Period %s to %s · generated %s (Philippine time)".formatted(data.from(), data.to(),
                    STAMP.format(data.generatedAt()));
            summary(wb, st, data, period);
            daily(wb, st, data, period);
            transactions(wb, st, data, period);
            targets(wb, st, policies);
            definitions(wb, st);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Excel export failed", e);
        }
    }

    private void summary(SXSSFWorkbook wb, Styles st, AgentWorkbook data, String period) {
        Sheet sh = sheet(wb, "Summary", st, "Agent performance against SLA — every agent",
                period + " · " + data.agents().size() + " agents (agents without activity show NO DATA)",
                AGENT_HEADERS, new int[] {26, 16, 26, 22, 10, 11, 12, 10, 9, 14, 14, 12, 14, 12, 13, 12, 10, 11});
        int r = 4;
        for (AgentPerformanceRow a : data.agents()) {
            agentCells(sh.createRow(r++), 0, a, st);
        }
        finish(sh, 3, r - 1, AGENT_HEADERS.length);
    }

    private void daily(SXSSFWorkbook wb, Styles st, AgentWorkbook data, String period) {
        String[] headers = new String[AGENT_HEADERS.length + 1];
        headers[0] = "Date";
        System.arraycopy(AGENT_HEADERS, 0, headers, 1, AGENT_HEADERS.length);
        Sheet sh = sheet(wb, "Daily breakdown", st, "Agent performance per business day", period, headers,
                new int[] {11, 26, 16, 26, 22, 10, 11, 12, 10, 9, 14, 14, 12, 14, 12, 13, 12, 10, 11});
        int r = 4;
        for (AgentDailyRow d : data.daily()) {
            Row row = sh.createRow(r++);
            text(row, 0, d.date().toString(), st.text());
            agentCells(row, 1, d.metrics(), st);
        }
        finish(sh, 3, r - 1, headers.length);
    }

    private void transactions(SXSSFWorkbook wb, Styles st, AgentWorkbook data, String period) {
        String[] headers = {"Date", "Ticket", "Agent", "Username", "Site", "Service", "Wait (min)",
                "Wait target (min)", "Wait within target", "Processing (min)", "Processing target (min)",
                "Processing within target", "Outcome"};
        Sheet sh = sheet(wb, "Transactions", st, "Every client handled, per agent and service stage",
                period + " · " + data.transactions().size() + " transactions", headers,
                new int[] {11, 9, 26, 16, 26, 40, 10, 12, 12, 13, 15, 15, 18});
        int r = 4;
        for (AgentTransactionRow t : data.transactions()) {
            Row row = sh.createRow(r++);
            text(row, 0, t.businessDate().toString(), st.text());
            text(row, 1, t.ticketNumber(), st.text());
            text(row, 2, t.agentName(), st.text());
            text(row, 3, t.username(), st.text());
            text(row, 4, t.siteName(), st.text());
            text(row, 5, t.serviceName(), st.text());
            number(row, 6, t.waitMinutes(), st.decimal());
            number(row, 7, t.waitTargetMinutes() == null ? null : t.waitTargetMinutes().doubleValue(), st.integer());
            text(row, 8, yesNo(t.waitWithinTarget()), st.text());
            number(row, 9, t.processingMinutes(), st.decimal());
            number(row, 10, t.processingTargetMinutes() == null ? null : t.processingTargetMinutes().doubleValue(), st.integer());
            text(row, 11, yesNo(t.processingWithinTarget()), st.text());
            text(row, 12, t.outcome(), st.text());
        }
        finish(sh, 3, r - 1, headers.length);
    }

    private void targets(SXSSFWorkbook wb, Styles st, List<SlaPolicyDto> policies) {
        String[] headers = {"Region", "Source", "Waiting target (min)", "Processing target (min)",
                "Compliance target", "Service overrides"};
        Sheet sh = sheet(wb, "SLA targets", st, "SLA targets in force (Admin › SLA Targets)",
                "Per-service overrides take precedence over the region's targets", headers,
                new int[] {30, 26, 16, 18, 14, 80});
        int r = 4;
        for (SlaPolicyDto p : policies) {
            Row row = sh.createRow(r++);
            text(row, 0, p.regionName(), st.text());
            text(row, 1, p.regionId() == null ? "National default" : p.inherited() ? "Uses national default" : "Own SLA",
                    st.text());
            number(row, 2, (double) p.waitingTargetMinutes(), st.integer());
            number(row, 3, (double) p.processingTargetMinutes(), st.integer());
            number(row, 4, p.complianceTargetPercent().doubleValue() / 100.0, st.percent());
            text(row, 5, p.inherited() || p.serviceTargets().isEmpty() ? "" : String.join("; ", p.serviceTargets()
                    .stream().map(t -> "%s: wait %s / process %s min".formatted(t.serviceCode(),
                            t.waitingTargetMinutes() == null ? "—" : t.waitingTargetMinutes(),
                            t.processingTargetMinutes() == null ? "—" : t.processingTargetMinutes())).toList()), st.text());
        }
        finish(sh, 3, r - 1, headers.length);
    }

    private void definitions(SXSSFWorkbook wb, Styles st) {
        Sheet sh = wb.createSheet("Definitions");
        sh.setColumnWidth(0, 30 * 256);
        sh.setColumnWidth(1, 110 * 256);
        String[][] rows = {
                {"Handled", "Clients the agent called (or resumed) at one service stage. A client transferred between agents counts once for each agent."},
                {"Completed / Transferred / No-shows", "How the agent's part ended: completed, sent to another service, or removed because the client did not respond."},
                {"Recalls", "Times the agent re-announced a ticket already at their counter."},
                {"Avg processing (min)", "The agent's own time at the counter per finished transaction (time on hold excluded; no-shows and transactions still in progress excluded)."},
                {"Processing within target", "Share of finished transactions whose processing time was within the processing target that applied (service override, else the region's SLA, else the national default)."},
                {"Avg wait (min)", "How long the clients this agent called had waited in the queue (since issue or since being transferred into the stage)."},
                {"Waiting within target", "Share of those calls where the client's wait was within the waiting target. Reported for information; it depends on staffing as much as on the agent."},
                {"SLA", "MET when processing within target is at least the region's compliance target; NOT MET otherwise; NO DATA when the agent finished no transaction in the period."},
                {"Source", "Computed from the ticket event log (every call, hold, resume, transfer and completion), so each figure can be traced to the Transactions sheet."},
        };
        Row title = sh.createRow(0);
        text(title, 0, "Column definitions", st.title());
        int r = 2;
        for (String[] d : rows) {
            Row row = sh.createRow(r++);
            text(row, 0, d[0], st.header());
            text(row, 1, d[1], st.wrap());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void agentCells(Row row, int c, AgentPerformanceRow a, Styles st) {
        text(row, c, a.agentName(), st.text());
        text(row, c + 1, a.username(), st.text());
        text(row, c + 2, a.siteName(), st.text());
        text(row, c + 3, a.regionName(), st.text());
        number(row, c + 4, (double) a.handled(), st.integer());
        number(row, c + 5, (double) a.completed(), st.integer());
        number(row, c + 6, (double) a.transferred(), st.integer());
        number(row, c + 7, (double) a.noShows(), st.integer());
        number(row, c + 8, (double) a.recalls(), st.integer());
        number(row, c + 9, a.avgProcessingMinutes(), st.decimal());
        number(row, c + 10, a.processingWithinTargetPercent() == null ? null : a.processingWithinTargetPercent() / 100.0,
                st.percent());
        number(row, c + 11, a.avgWaitMinutes(), st.decimal());
        number(row, c + 12, a.waitingWithinTargetPercent() == null ? null : a.waitingWithinTargetPercent() / 100.0,
                st.percent());
        number(row, c + 13, a.waitingTargetMinutes() == null ? null : a.waitingTargetMinutes().doubleValue(), st.integer());
        number(row, c + 14, a.processingTargetMinutes() == null ? null : a.processingTargetMinutes().doubleValue(),
                st.integer());
        number(row, c + 15, a.complianceTargetPercent() == null ? null : a.complianceTargetPercent() / 100.0, st.percent());
        text(row, c + 16, a.serviceOverrides() ? "Yes" : "No", st.text());
        CellStyle status = switch (a.slaStatus()) {
            case MET -> st.met();
            case NOT_MET -> st.notMet();
            case NO_DATA -> st.noData();
        };
        text(row, c + 17, a.slaStatus().name().replace('_', ' '), status);
    }

    private Sheet sheet(SXSSFWorkbook wb, String name, Styles st, String title, String subtitle, String[] headers,
                        int[] widths) {
        Sheet sh = wb.createSheet(name);
        text(sh.createRow(0), 0, title, st.title());
        text(sh.createRow(1), 0, subtitle, st.subtitle());
        Row header = sh.createRow(3);
        header.setHeightInPoints(30);
        for (int i = 0; i < headers.length; i++) {
            text(header, i, headers[i], st.header());
            sh.setColumnWidth(i, Math.min(255, widths[i]) * 256);
        }
        sh.createFreezePane(0, 4);
        return sh;
    }

    private static void finish(Sheet sh, int headerRow, int lastRow, int columns) {
        sh.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, lastRow), 0, columns - 1));
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) {
            c.setCellValue(value);
        }
        c.setCellStyle(style);
    }

    private static void number(Row row, int col, Double value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) {
            c.setCellValue(value);
        }
        c.setCellStyle(style);
    }

    private static String yesNo(Boolean b) {
        return b == null ? "" : b ? "Yes" : "No";
    }

    private static Styles styles(SXSSFWorkbook wb) {
        Font bold = wb.createFont();
        bold.setBold(true);
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        Font grey = wb.createFont();
        grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        Font white = wb.createFont();
        white.setBold(true);
        white.setColor(IndexedColors.WHITE.getIndex());

        CellStyle title = wb.createCellStyle();
        title.setFont(titleFont);
        CellStyle subtitle = wb.createCellStyle();
        subtitle.setFont(grey);
        CellStyle header = wb.createCellStyle();
        header.setFont(white);
        header.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setWrapText(true);
        header.setBorderBottom(BorderStyle.THIN);
        CellStyle text = wb.createCellStyle();
        CellStyle integer = wb.createCellStyle();
        integer.setDataFormat(wb.createDataFormat().getFormat("0"));
        CellStyle decimal = wb.createCellStyle();
        decimal.setDataFormat(wb.createDataFormat().getFormat("0.0"));
        CellStyle percent = wb.createCellStyle();
        percent.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
        CellStyle met = status(wb, bold, IndexedColors.LIGHT_GREEN);
        CellStyle notMet = status(wb, bold, IndexedColors.ROSE);
        CellStyle noData = status(wb, bold, IndexedColors.GREY_25_PERCENT);
        CellStyle wrap = wb.createCellStyle();
        wrap.setWrapText(true);
        return new Styles(title, subtitle, header, text, integer, decimal, percent, met, notMet, noData, wrap);
    }

    private static CellStyle status(SXSSFWorkbook wb, Font bold, IndexedColors color) {
        CellStyle s = wb.createCellStyle();
        s.setFont(bold);
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }
}

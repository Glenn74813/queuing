package ph.gov.philhealth.qms.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryReport;
import ph.gov.philhealth.qms.report.ReportDtos.SummaryRow;

/**
 * Generic CSV/XLSX/PDF export of a summary report. Official PhilHealth report templates are not yet
 * available; when provided they will be implemented as additional configurable layouts (OI-08).
 */
@Component
public class ReportExporter {

    private static final String[] HEADER = {"Group", "Issued", "Served", "Abandoned", "Voided", "Rerouted",
            "Avg queueing time (min)", "Avg waiting time (min)", "Avg processing time (min)", "Feedback count",
            "Avg rating"};

    public byte[] csv(SummaryReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("PhilHealth QMS summary,").append(report.from()).append(" to ").append(report.to())
                .append(",grouped by ").append(report.groupBy()).append(",metrics v")
                .append(report.metricDefinitionsVersion()).append("\r\n");
        sb.append(String.join(",", HEADER)).append("\r\n");
        for (SummaryRow r : withTotals(report)) {
            List<String> cells = values(r);
            sb.append(String.join(",", cells.stream().map(ReportExporter::csvCell).toList())).append("\r\n");
        }
        return withBom(sb.toString());
    }

    public byte[] xlsx(SummaryReport report) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Summary");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("PhilHealth QMS summary " + report.from() + " to " + report.to()
                    + " by " + report.groupBy() + " (metrics v" + report.metricDefinitionsVersion() + ")");
            Row header = sheet.createRow(2);
            for (int i = 0; i < HEADER.length; i++) {
                header.createCell(i).setCellValue(HEADER[i]);
            }
            int rowNum = 3;
            for (SummaryRow r : withTotals(report)) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(r.label());
                double[] nums = {r.issued(), r.served(), r.abandoned(), r.voided(), r.rerouted()};
                for (int i = 0; i < nums.length; i++) {
                    row.createCell(i + 1).setCellValue(nums[i]);
                }
                setNullable(row, 6, r.avgQueueMinutes());
                setNullable(row, 7, r.avgWaitMinutes());
                setNullable(row, 8, r.avgProcessMinutes());
                row.createCell(9).setCellValue(r.feedbackCount());
                setNullable(row, 10, r.avgRating());
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("XLSX export failed", e);
        }
    }

    /** Landscape A4 table; the layout is generic until official templates are provided (OI-08). */
    public byte[] pdf(SummaryReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        doc.add(new Paragraph("PhilHealth QMS - Queue Performance Summary", title));
        doc.add(new Paragraph("Period: %s to %s   Grouped by: %s   Metric definitions v%s   Generated: %s"
                .formatted(report.from(), report.to(), report.groupBy(), report.metricDefinitionsVersion(),
                        report.generatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + " UTC"), small));
        doc.add(new Paragraph(" ", small));

        PdfPTable table = new PdfPTable(HEADER.length);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {4.5f, 1, 1, 1.2f, 1, 1.1f, 1.5f, 1.5f, 1.6f, 1.2f, 1});
        table.setHeaderRows(1);
        for (String h : HEADER) {
            PdfPCell cell = new PdfPCell(new Phrase(h, head));
            cell.setGrayFill(0.85f);
            table.addCell(cell);
        }
        List<SummaryRow> rows = withTotals(report);
        for (int i = 0; i < rows.size(); i++) {
            Font f = i == rows.size() - 1 ? bold : small;
            List<String> cells = values(rows.get(i));
            for (int c = 0; c < cells.size(); c++) {
                PdfPCell cell = new PdfPCell(new Phrase(cells.get(c), f));
                if (c > 0) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        doc.add(table);
        doc.add(new Paragraph("Times in minutes. Definitions: docs/metrics-definitions.md. "
                + "Layout is not an official PhilHealth template.", small));
        doc.close();
        return out.toByteArray();
    }

    /** Any tabular report as CSV, XLSX or PDF, with a title line and a subtitle line. */
    public byte[] table(String format, String title, String subtitle, List<String> headers, List<List<String>> rows) {
        return switch (format) {
            case "csv" -> {
                StringBuilder sb = new StringBuilder();
                sb.append(csvCell(title)).append(",").append(csvCell(subtitle)).append("\r\n");
                sb.append(String.join(",", headers.stream().map(ReportExporter::csvCell).toList())).append("\r\n");
                rows.forEach(r -> sb.append(String.join(",", r.stream().map(ReportExporter::csvCell).toList()))
                        .append("\r\n"));
                yield withBom(sb.toString());
            }
            case "xlsx" -> {
                try (SXSSFWorkbook wb = new SXSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    Sheet sheet = wb.createSheet("Report");
                    sheet.createRow(0).createCell(0).setCellValue(title);
                    sheet.createRow(1).createCell(0).setCellValue(subtitle);
                    Row header = sheet.createRow(3);
                    for (int i = 0; i < headers.size(); i++) {
                        header.createCell(i).setCellValue(headers.get(i));
                    }
                    int n = 4;
                    for (List<String> r : rows) {
                        Row row = sheet.createRow(n++);
                        for (int i = 0; i < r.size(); i++) {
                            String v = r.get(i);
                            if (v != null && v.matches("-?\\d+(\\.\\d+)?")) {
                                row.createCell(i).setCellValue(Double.parseDouble(v));
                            } else {
                                row.createCell(i).setCellValue(v == null ? "" : v);
                            }
                        }
                    }
                    wb.write(out);
                    wb.dispose();
                    yield out.toByteArray();
                } catch (IOException e) {
                    throw new IllegalStateException("XLSX export failed", e);
                }
            }
            case "pdf" -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
                PdfWriter.getInstance(doc, out);
                doc.open();
                Font small = FontFactory.getFont(FontFactory.HELVETICA, 7);
                Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);
                doc.add(new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13)));
                doc.add(new Paragraph(subtitle, FontFactory.getFont(FontFactory.HELVETICA, 8)));
                doc.add(new Paragraph(" ", small));
                PdfPTable t = new PdfPTable(headers.size());
                t.setWidthPercentage(100);
                t.setHeaderRows(1);
                for (String h : headers) {
                    PdfPCell c = new PdfPCell(new Phrase(h, head));
                    c.setGrayFill(0.85f);
                    t.addCell(c);
                }
                for (List<String> r : rows) {
                    for (String v : r) {
                        PdfPCell c = new PdfPCell(new Phrase(v == null ? "" : v, small));
                        if (v != null && v.matches("-?[\\d.%]+")) {
                            c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        }
                        t.addCell(c);
                    }
                }
                doc.add(t);
                doc.close();
                yield out.toByteArray();
            }
            default -> throw new IllegalArgumentException("Unsupported format " + format);
        };
    }

    /** Ticket-level CSV: one row per ticket, the source records behind every summary figure. */
    public byte[] ticketsCsv(List<ReportDtos.TicketReportRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", "Ticket ID", "Number", "Business date", "Site", "Region", "Current service",
                "Client type", "Service type", "Classification", "Priority", "Channel", "Status", "Stage",
                "Counter", "Agent", "Issued at (UTC)", "First called at (UTC)", "Completed at (UTC)",
                "Queue seconds", "Wait seconds", "Service seconds", "Hold seconds", "Transfers", "Recalls",
                "Device")).append("\r\n");
        for (ReportDtos.TicketReportRow r : rows) {
            List<String> cells = List.of(str(r.id()), str(r.displayNumber()), str(r.businessDate()), str(r.siteName()),
                    str(r.regionName()), str(r.serviceName()), str(r.clientType()), str(r.serviceType()),
                    str(r.classification()), str(r.priorityCode()), str(r.channel()), str(r.status()),
                    str(r.stageNo()), str(r.counterName()), str(r.agentName()), str(r.issuedAt()),
                    str(r.firstCalledAt()), str(r.completedAt()), str(r.queueSeconds()), str(r.waitSeconds()),
                    str(r.serviceSeconds()), str(r.holdSeconds()), str(r.transferCount()), str(r.recallCount()),
                    str(r.deviceCode()));
            sb.append(String.join(",", cells.stream().map(ReportExporter::csvCell).toList())).append("\r\n");
        }
        return withBom(sb.toString());
    }

    /** UTF-8 with BOM so Excel opens Filipino names (ñ) correctly. */
    public static byte[] withBom(String csv) {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static void setNullable(Row row, int col, Double v) {
        if (v != null) {
            row.createCell(col).setCellValue(v);
        }
    }

    private static List<SummaryRow> withTotals(SummaryReport report) {
        List<SummaryRow> all = new ArrayList<>(report.rows());
        all.add(report.totals());
        return all;
    }

    private static List<String> values(SummaryRow r) {
        return List.of(r.label(), str(r.issued()), str(r.served()), str(r.abandoned()), str(r.voided()),
                str(r.rerouted()), str(r.avgQueueMinutes()), str(r.avgWaitMinutes()), str(r.avgProcessMinutes()),
                str(r.feedbackCount()), str(r.avgRating()));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Quotes the cell and neutralizes spreadsheet formula injection (=, +, -, @ at the start). */
    public static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

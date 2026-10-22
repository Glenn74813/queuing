package ph.gov.philhealth.qms.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportExporterTest {

    @Test
    void neutralizesFormulaInjection() {
        assertThat(ReportExporter.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportExporter.csvCell("@SUM(A1)")).startsWith("\"'@");
        assertThat(ReportExporter.csvCell("-12.5")).isEqualTo("\"-12.5\"");
        assertThat(ReportExporter.csvCell("Walk-in")).isEqualTo("\"Walk-in\"");
    }
}

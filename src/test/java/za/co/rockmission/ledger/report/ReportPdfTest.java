package za.co.rockmission.ledger.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportPdfTest {

    @Test
    void rendersEverySectionAndPaginatesLongEntryLists() throws Exception {
        Map<String, Object> totals = new HashMap<>(Map.of(
            "income", new BigDecimal("12500.00"), "expense", new BigDecimal("4300.50"), "net", new BigDecimal("8199.50"),
            "loans_in", new BigDecimal("5000.00"), "loans_out", BigDecimal.ZERO, "transfers", BigDecimal.ZERO, "entries", 120L));
        Map<String, Object> summary = new HashMap<>();
        summary.put("from", LocalDate.of(2026, 5, 1));
        summary.put("to", LocalDate.of(2027, 4, 30));
        summary.put("totals", totals);
        summary.put("monthly", List.of(Map.of("month", "2026-05", "income", new BigDecimal("12500.00"), "expense", new BigDecimal("4300.50"))));
        summary.put("byCategory", List.of(Map.of("kind", "INCOME", "category", "Tithes", "total", new BigDecimal("12500.00"))));
        summary.put("byFund", List.of(Map.of("fund", "Building", "restricted", true, "income", new BigDecimal("12500.00"), "expense", new BigDecimal("4300.50"))));
        summary.put("balances", List.of(Map.of("name", "Capitec Business", "type", "BANK", "balance", new BigDecimal("8199.50"))));
        summary.put("loans", List.of(Map.of("lender", "J Director", "owed", new BigDecimal("5000.00"))));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Map<String, Object> e = new HashMap<>();
            e.put("txn_date", Date.valueOf(LocalDate.of(2026, 5, 1).plusDays(i)));
            e.put("kind", i % 2 == 0 ? "INCOME" : "EXPENSE");
            e.put("amount", new BigDecimal("100.00"));
            e.put("account", "Capitec Business");
            e.put("fund", "General");
            e.put("category", "Offerings");
            e.put("counterparty", i == 3 ? "Emoji donor 🙏 with a very long name that must be cut off neatly" : "Donor " + i);
            e.put("reversal_of_id", i == 5 ? 4L : null);
            entries.add(e);
        }

        byte[] pdf = ReportPdf.render("Rock Mission Ministries NPC", summary, entries);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Financial report", "1 May 2026 to 30 Apr 2027", "Summary", "12 500.00",
                "By category", "Tithes", "Building (restricted)", "Capitec Business", "J Director", "Entries", "(rev)");
        }
    }

    @Test
    void csvCellsAreQuotedAndFormulaSafe() {
        assertThat(PeriodReportController.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(PeriodReportController.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(PeriodReportController.cell("=HYPERLINK(\"x\")")).startsWith("\"'=");
        assertThat(PeriodReportController.cell(new BigDecimal("-12.50"))).isEqualTo("-12.50");
        assertThat(PeriodReportController.cell(null)).isEmpty();
    }
}

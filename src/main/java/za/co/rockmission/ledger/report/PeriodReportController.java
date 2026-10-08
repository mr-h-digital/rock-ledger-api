package za.co.rockmission.ledger.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Dashboard figures and downloadable reports for any date range. */
@RestController
@RequestMapping("/api/reports")
public class PeriodReportController {

    private final PeriodReport reports;

    public PeriodReportController(PeriodReport reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(PeriodReport.Period.of(from, to));
    }

    @GetMapping("/transactions.csv")
    public ResponseEntity<byte[]> transactionsCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        PeriodReport.Period p = PeriodReport.Period.of(from, to);
        StringBuilder csv = new StringBuilder("\uFEFF"); // BOM so Excel opens it as UTF-8
        csv.append("Date,Type,Amount,Signed amount,Account,To account,Fund,Category,Channel,Counterparty,Reference,Reversal of,Entry id,Captured by\r\n");
        for (Map<String, Object> r : reports.entries(p)) {
            BigDecimal amount = (BigDecimal) r.get("amount");
            String kind = (String) r.get("kind");
            boolean reversal = r.get("reversal_of_id") != null;
            BigDecimal signed = ("INCOME".equals(kind) || "LOAN_IN".equals(kind) ? amount : amount.negate());
            if ("TRANSFER".equals(kind)) signed = BigDecimal.ZERO;
            if (reversal) signed = signed.negate();
            row(csv, r.get("txn_date"), kind, amount, signed, r.get("account"),
                r.get("to_account"), r.get("fund"), r.get("category"), r.get("channel"), r.get("counterparty"),
                r.get("reference"), r.get("reversal_of_id"), r.get("id"), r.get("created_by"));
        }
        return download(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8",
            "rock-ledger-transactions-" + p.from() + "-to-" + p.to() + ".csv");
    }

    @GetMapping("/summary.csv")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> summaryCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        PeriodReport.Period p = PeriodReport.Period.of(from, to);
        Map<String, Object> s = reports.summary(p);
        Map<String, Object> t = (Map<String, Object>) s.get("totals");
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "Section", "Item", "Detail", "Income", "Expenses", "Amount");
        row(csv, "Period", p.from() + " to " + p.to(), "", "", "", "");
        row(csv, "Summary", "Income", "", "", "", t.get("income"));
        row(csv, "Summary", "Expenses", "", "", "", t.get("expense"));
        row(csv, "Summary", "Surplus / (deficit)", "", "", "", t.get("net"));
        row(csv, "Summary", "Loans received", "", "", "", t.get("loans_in"));
        row(csv, "Summary", "Loans repaid", "", "", "", t.get("loans_out"));
        row(csv, "Summary", "Transfers", "", "", "", t.get("transfers"));
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("monthly")) {
            row(csv, "Month", r.get("month"), "", r.get("income"), r.get("expense"),
                ((BigDecimal) r.get("income")).subtract((BigDecimal) r.get("expense")));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("byCategory")) {
            row(csv, "Category", r.get("category"), "INCOME".equals(r.get("kind")) ? "Income" : "Expense", "", "", r.get("total"));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("byFund")) {
            row(csv, "Fund", r.get("fund"), Boolean.TRUE.equals(r.get("restricted")) ? "Restricted" : "Unrestricted",
                r.get("income"), r.get("expense"), ((BigDecimal) r.get("income")).subtract((BigDecimal) r.get("expense")));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("balances")) {
            row(csv, "Balance at " + p.to(), r.get("name"), r.get("type"), "", "", r.get("balance"));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("loans")) {
            row(csv, "Loan owed at " + p.to(), r.get("lender"), "", "", "", r.get("owed"));
        }
        return download(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=utf-8",
            "rock-ledger-summary-" + p.from() + "-to-" + p.to() + ".csv");
    }

    @GetMapping("/report.pdf")
    public ResponseEntity<byte[]> pdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws IOException {
        PeriodReport.Period p = PeriodReport.Period.of(from, to);
        byte[] pdf = ReportPdf.render(reports.organisationName(), reports.summary(p), reports.entries(p));
        return download(pdf, MediaType.APPLICATION_PDF_VALUE, "rock-ledger-report-" + p.from() + "-to-" + p.to() + ".pdf");
    }

    private static ResponseEntity<byte[]> download(byte[] body, String type, String name) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType(type))
            .body(body);
    }

    private static void row(StringBuilder csv, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(cell(cells[i]));
        }
        csv.append("\r\n");
    }

    /** Quotes when needed and neutralises text that a spreadsheet would run as a formula. */
    static String cell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        boolean number = v instanceof Number;
        if (!number && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

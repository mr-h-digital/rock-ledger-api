package za.co.rockmission.ledger.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Lays out the period report as an A4 PDF: summary, monthly, categories, funds, balances, loans and every entry. */
final class ReportPdf {

    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final Color INK = new Color(11, 18, 32);
    private static final Color MUTED = new Color(104, 115, 134);
    private static final Color GOLD = new Color(182, 141, 28);
    private static final Color LINE = new Color(231, 234, 240);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final DecimalFormat money;
    private final PDDocument doc = new PDDocument();
    private PDPageContentStream out;
    private float y;
    private int pageNo;

    private ReportPdf() {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ENGLISH);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator('.');
        money = new DecimalFormat("#,##0.00", s);
    }

    static byte[] render(String organisation, Map<String, Object> summary, List<Map<String, Object>> entries) throws IOException {
        ReportPdf pdf = new ReportPdf();
        try (pdf.doc) {
            pdf.write(organisation, summary, entries);
            pdf.out.close();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdf.doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void write(String organisation, Map<String, Object> s, List<Map<String, Object>> entries) throws IOException {
        newPage();
        LocalDate from = (LocalDate) s.get("from");
        LocalDate to = (LocalDate) s.get("to");
        text(organisation.toUpperCase(Locale.ENGLISH), MARGIN, bold, 9, GOLD);
        y -= 22;
        text("Financial report", MARGIN, bold, 20, INK);
        y -= 16;
        text(DAY.format(from) + " to " + DAY.format(to), MARGIN, regular, 10, MUTED);
        y -= 22;

        Map<String, Object> t = (Map<String, Object>) s.get("totals");
        heading("Summary");
        table(new String[] {"", "Amount (R)"}, new float[] {.7f, .3f}, List.of(
            new String[] {"Income", amt(t.get("income"))},
            new String[] {"Expenses", amt(t.get("expense"))},
            new String[] {"Surplus / (deficit)", amt(t.get("net"))},
            new String[] {"Loans received", amt(t.get("loans_in"))},
            new String[] {"Loans repaid", amt(t.get("loans_out"))},
            new String[] {"Transfers between accounts", amt(t.get("transfers"))},
            new String[] {"Number of entries", String.valueOf(t.get("entries"))}), 2);

        heading("Income and expenses by month");
        table(new String[] {"Month", "Income", "Expenses", "Net"}, new float[] {.34f, .22f, .22f, .22f},
            rows((List<Map<String, Object>>) s.get("monthly"), r -> new String[] {
                monthLabel((String) r.get("month")), amt(r.get("income")), amt(r.get("expense")),
                amt(dec(r.get("income")).subtract(dec(r.get("expense"))))}), -1);

        heading("By category");
        table(new String[] {"Type", "Category", "Amount (R)"}, new float[] {.2f, .5f, .3f},
            rows((List<Map<String, Object>>) s.get("byCategory"), r -> new String[] {
                "INCOME".equals(r.get("kind")) ? "Income" : "Expense", str(r.get("category")), amt(r.get("total"))}), -1);

        heading("By fund");
        table(new String[] {"Fund", "Income", "Expenses", "Net"}, new float[] {.34f, .22f, .22f, .22f},
            rows((List<Map<String, Object>>) s.get("byFund"), r -> new String[] {
                str(r.get("fund")) + (Boolean.TRUE.equals(r.get("restricted")) ? " (restricted)" : ""),
                amt(r.get("income")), amt(r.get("expense")), amt(dec(r.get("income")).subtract(dec(r.get("expense"))))}), -1);

        heading("Account balances at " + DAY.format(to));
        table(new String[] {"Account", "Type", "Balance (R)"}, new float[] {.5f, .2f, .3f},
            rows((List<Map<String, Object>>) s.get("balances"), r -> new String[] {
                str(r.get("name")), cap(str(r.get("type"))), amt(r.get("balance"))}), -1);

        List<Map<String, Object>> loans = (List<Map<String, Object>>) s.get("loans");
        if (!loans.isEmpty()) {
            heading("Director loans owed at " + DAY.format(to));
            table(new String[] {"Lender", "Owed (R)"}, new float[] {.7f, .3f},
                rows(loans, r -> new String[] {str(r.get("lender")), amt(r.get("owed"))}), -1);
        }

        heading("Entries");
        table(new String[] {"Date", "Type", "Description", "Category / fund", "Amount (R)"}, new float[] {.13f, .12f, .33f, .24f, .18f},
            rows(entries, r -> {
                boolean reversal = r.get("reversal_of_id") != null;
                String desc = firstNonBlank(str(r.get("counterparty")), str(r.get("reference")), "");
                if ("TRANSFER".equals(r.get("kind"))) desc = str(r.get("account")) + " → " + str(r.get("to_account"));
                return new String[] {
                    r.get("txn_date").toString(), kindLabel(str(r.get("kind"))) + (reversal ? " (rev)" : ""), desc,
                    firstNonBlank(str(r.get("category")), "") + " / " + str(r.get("fund")),
                    (reversal ? "-" : "") + amt(r.get("amount"))};
            }), -1);

        y -= 10;
        need(30);
        text("Reversal entries are shown with \"(rev)\" and count as negative. Totals exclude amounts that were reversed.",
            MARGIN, regular, 7.5f, MUTED);
    }

    // ---- layout helpers ----------------------------------------------------------------------

    private interface RowMapper { String[] map(Map<String, Object> r); }

    private static List<String[]> rows(List<Map<String, Object>> src, RowMapper m) {
        return src.stream().map(m::map).toList();
    }

    private void heading(String title) throws IOException {
        need(60);
        y -= 8;
        text(title, MARGIN, bold, 12, INK);
        y -= 8;
    }

    /** Draws a table; numeric columns (all but the first) are right-aligned. Bolds row {@code boldRow} if >= 0. */
    private void table(String[] head, float[] widths, List<String[]> body, int boldRow) throws IOException {
        float size = 8.5f;
        float rowH = 15;
        drawRow(head, widths, bold, size, MUTED, true);
        if (body.isEmpty()) {
            need(rowH);
            y -= rowH;
            text("Nothing in this period", MARGIN + 4, regular, size, MUTED);
        }
        for (int i = 0; i < body.size(); i++) {
            if (y - rowH < MARGIN + 20) { newPage(); drawRow(head, widths, bold, size, MUTED, true); }
            drawRow(body.get(i), widths, i == boldRow ? bold : regular, size, INK, false);
        }
        y -= 14;
    }

    private void drawRow(String[] cells, float[] widths, PDFont font, float size, Color color, boolean header) throws IOException {
        float rowH = 15;
        need(rowH);
        y -= rowH;
        float x = MARGIN;
        for (int c = 0; c < cells.length; c++) {
            float w = widths[c] * WIDTH;
            String v = fit(cells[c] == null ? "" : cells[c], font, size, w - 8);
            boolean right = c > 0 && looksNumeric(cells[c]);
            float tx = right ? x + w - 4 - width(v, font, size) : x + 4;
            text(v, tx, font, size, color);
            x += w;
        }
        out.setStrokingColor(header ? MUTED : LINE);
        out.setLineWidth(header ? .6f : .4f);
        out.moveTo(MARGIN, y - 4);
        out.lineTo(MARGIN + WIDTH, y - 4);
        out.stroke();
    }

    private void need(float h) throws IOException {
        if (y - h < MARGIN + 20) newPage();
    }

    private void newPage() throws IOException {
        if (out != null) out.close();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        out = new PDPageContentStream(doc, page);
        pageNo++;
        y = page.getMediaBox().getHeight() - MARGIN;
        String footer = "Rock Ledger · generated " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH))
            + " · page " + pageNo;
        float keep = y;
        y = MARGIN - 14;
        text(footer, MARGIN, regular, 7, MUTED);
        y = keep;
    }

    private void text(String s, float x, PDFont font, float size, Color color) throws IOException {
        out.beginText();
        out.setFont(font, size);
        out.setNonStrokingColor(color);
        out.newLineAtOffset(x, y);
        out.showText(safe(s, font));
        out.endText();
    }

    private float width(String s, PDFont font, float size) throws IOException {
        return font.getStringWidth(safe(s, font)) / 1000 * size;
    }

    private String fit(String s, PDFont font, float size, float max) throws IOException {
        String v = safe(s, font);
        if (width(v, font, size) <= max) return v;
        while (v.length() > 1 && width(v + "...", font, size) > max) v = v.substring(0, v.length() - 1);
        return v + "...";
    }

    /** The standard fonts only cover WinAnsi; replace anything else so a stray emoji cannot break the report. */
    private static String safe(String s, PDFont font) {
        StringBuilder b = new StringBuilder(s.length());
        s.replace('→', '>').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                b.append(ch);
            } catch (Exception e) {
                b.append('?');
            }
        });
        return b.toString();
    }

    private static boolean looksNumeric(String s) {
        return s != null && s.matches("-?[\\d ]+(\\.\\d+)?");
    }

    private String amt(Object v) {
        return money.format(dec(v));
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ENGLISH);
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "INCOME" -> "Income";
            case "EXPENSE" -> "Expense";
            case "LOAN_IN" -> "Loan in";
            case "LOAN_OUT" -> "Loan repaid";
            case "TRANSFER" -> "Transfer";
            default -> kind;
        };
    }

    private static String monthLabel(String yyyyMm) {
        LocalDate d = LocalDate.parse(yyyyMm + "-01");
        return d.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
    }
}

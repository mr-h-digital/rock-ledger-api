package za.co.rockmission.ledger.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Capitec Business statement text (extracted from the PDF) in both layouts seen so far:
 *  - OLD (until June 2026): "Post Date / Trans Date / Description / Fees / Amount / Balance", signed numbers.
 *  - NEW (from July 2026): "Date / Description / Category / Money In / Money Out / Fee / Balance".
 * Every line carries a running balance, so each line's amount is derived as
 * (balance after - balance before). That makes the parse self-checking: if a line is missed,
 * the closing balance will not match.
 */
public final class StatementParser {

    /** amount excludes the bank fee; fee is zero or negative and is posted as a separate "Bank charges" expense. */
    public record Line(LocalDate postDate, LocalDate txnDate, String description,
                       BigDecimal amount, BigDecimal fee, BigDecimal balanceAfter) {}

    public record Parsed(String layout, BigDecimal opening, BigDecimal closing,
                         LocalDate from, LocalDate to, List<Line> lines, List<String> warnings) {}

    private static final String NUM = "(?:\\d{1,3}(?: \\d{3})+|\\d+)\\.\\d{2}";
    private static final Pattern SIGNED = Pattern.compile("(?<![\\w.])([+-])(" + NUM + ")(?!\\d)");
    private static final Pattern OLD_LINE =
        Pattern.compile("^\\s*(\\d{2}/\\d{2}/\\d{2})\\s+(\\d{2}/\\d{2}/\\d{2})\\s+(.*)$");
    private static final Pattern NEW_LINE =
        Pattern.compile("^\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(.*?)\\s+(-?" + NUM + ")\\s*$");
    private static final Pattern MONEY_TOKEN = Pattern.compile("\\s(-?" + NUM + ")(?=\\s|$)");
    private static final Pattern OLD_OPEN =
        Pattern.compile("Balance brought forward\\s+([+-]?" + NUM + ")");
    private static final Pattern NEW_OPEN = Pattern.compile("Opening Balance:\\s*R\\s*(-?" + NUM + ")");
    private static final Pattern NEW_CLOSE = Pattern.compile("Closing Balance:\\s*R\\s*(-?" + NUM + ")");
    private static final Pattern NEW_FROM = Pattern.compile("From Date:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern NEW_TO = Pattern.compile("To Date:\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern CATEGORY_TAIL = Pattern.compile(
        "\\s+(Uncategorised|Fuel|Groceries|Takeaways|Pharmacy|Digital Payments|Payments Received|Payments|"
        + "Municipal Bill|Cash Withdrawal|Fees|Investments|Transport|Shopping|Entertainment|Health|Utilities)$");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{6,}(\\d{4})\\b");
    private static final DateTimeFormatter D2 = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter D4 = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private StatementParser() {}

    public static Parsed parse(String text) {
        boolean isNew = NEW_OPEN.matcher(text).find();
        return isNew ? parseNew(text) : parseOld(text);
    }

    private static BigDecimal num(String s) {
        return new BigDecimal(s.replace(" ", "").replace("+", ""));
    }

    private static String clean(String description) {
        String d = description.trim().replaceAll("\\s+", " ");
        // hide full account / card numbers: keep only the last 4 digits
        return LONG_DIGITS.matcher(d).replaceAll("••••$1");
    }

    private static Parsed parseOld(String text) {
        List<String> warnings = new ArrayList<>();
        Matcher mo = OLD_OPEN.matcher(text);
        if (!mo.find()) {
            return new Parsed("OLD", null, null, null, null, List.of(),
                List.of("Could not find the opening balance; is this a Capitec Business statement?"));
        }
        BigDecimal opening = num(mo.group(1));
        BigDecimal prev = opening;
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            Matcher m = OLD_LINE.matcher(raw);
            if (!m.find()) continue;
            String rest = m.group(3);
            List<int[]> spans = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            Matcher s = SIGNED.matcher(rest);
            while (s.find()) {
                spans.add(new int[] {s.start(), s.end()});
                BigDecimal v = num(s.group(2));
                values.add(s.group(1).equals("-") ? v.negate() : v);
            }
            if (values.isEmpty()) continue;
            BigDecimal balance = values.get(values.size() - 1);
            String desc = rest.substring(0, spans.get(0)[0]);
            BigDecimal delta = balance.subtract(prev);
            if (delta.signum() == 0) {
                warnings.add("Skipped a zero-value line: " + clean(desc));
                continue;
            }
            BigDecimal fee = values.size() >= 3 && values.get(0).signum() < 0 ? values.get(0) : BigDecimal.ZERO;
            lines.add(new Line(LocalDate.parse(m.group(1), D2), LocalDate.parse(m.group(2), D2),
                    clean(desc), delta.subtract(fee), fee, balance));
            prev = balance;
        }
        LocalDate from = lines.stream().map(Line::txnDate).min(LocalDate::compareTo).orElse(null);
        LocalDate to = lines.stream().map(Line::txnDate).max(LocalDate::compareTo).orElse(null);
        if (lines.isEmpty()) warnings.add("No transaction lines found.");
        return new Parsed("OLD", opening, prev, from, to, lines, warnings);
    }

    private static Parsed parseNew(String text) {
        List<String> warnings = new ArrayList<>();
        Matcher mo = NEW_OPEN.matcher(text);
        mo.find();
        BigDecimal opening = num(mo.group(1));
        Matcher mc = NEW_CLOSE.matcher(text);
        BigDecimal closing = mc.find() ? num(mc.group(1)) : null;
        Matcher mf = NEW_FROM.matcher(text);
        Matcher mt = NEW_TO.matcher(text);
        LocalDate from = mf.find() ? LocalDate.parse(mf.group(1), D4) : null;
        LocalDate to = mt.find() ? LocalDate.parse(mt.group(1), D4) : null;

        BigDecimal prev = opening;
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.contains("Pending Card Transactions")) break; // not yet settled; appears on next statement
            Matcher m = NEW_LINE.matcher(raw);
            if (!m.find()) continue;
            BigDecimal balance = num(m.group(3));
            BigDecimal delta = balance.subtract(prev);
            String desc = m.group(2);
            String padded = " " + desc;
            Matcher t = MONEY_TOKEN.matcher(padded);
            List<BigDecimal> tokens = new ArrayList<>();
            int firstToken = -1;
            while (t.find()) {
                if (firstToken < 0) firstToken = t.start();
                tokens.add(num(t.group(1)));
            }
            if (firstToken >= 0) desc = padded.substring(0, firstToken).trim();
            BigDecimal fee = tokens.size() == 2 && tokens.get(1).signum() < 0 ? tokens.get(1) : BigDecimal.ZERO;
            desc = CATEGORY_TAIL.matcher(desc).replaceFirst("");
            if (delta.signum() == 0) {
                warnings.add("Skipped a zero-value line: " + clean(desc));
                continue;
            }
            LocalDate d = LocalDate.parse(m.group(1), D4);
            lines.add(new Line(d, d, clean(desc), delta.subtract(fee), fee, balance));
            prev = balance;
        }
        if (lines.isEmpty()) warnings.add("No transaction lines found.");
        if (closing != null && closing.compareTo(prev) != 0) {
            warnings.add("Closing balance " + closing + " does not match the last line (" + prev
                + "): a line may have been missed.");
        }
        return new Parsed("NEW", opening, closing != null ? closing : prev, from, to, lines, warnings);
    }
}

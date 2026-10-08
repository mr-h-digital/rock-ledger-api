package za.co.rockmission.ledger.report;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Figures for any date range, shared by the dashboard, the CSV export and the PDF report.
 * A reversal entry counts as negative, so a reversed entry and its reversal net to zero.
 */
@Component
public class PeriodReport {

    static final String SIGN = "(CASE WHEN t.reversal_of_id IS NULL THEN 1 ELSE -1 END)";
    private static final long MAX_DAYS = 366L * 10;

    private final JdbcTemplate jdbc;

    public PeriodReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Period(LocalDate from, LocalDate to) {
        public static Period of(LocalDate from, LocalDate to) {
            LocalDate end = to != null ? to : LocalDate.now();
            LocalDate start = from != null ? from : end.minusDays(90);
            if (start.isAfter(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date");
            if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of 10 years or less");
            }
            return new Period(start, end);
        }

        Date sqlFrom() { return Date.valueOf(from); }
        Date sqlTo() { return Date.valueOf(to); }
    }

    public Map<String, Object> summary(Period p) {
        Map<String, Object> totals = jdbc.queryForMap("""
            SELECT
              COALESCE(SUM(CASE WHEN t.kind = 'INCOME'   THEN t.amount END * %1$s), 0) AS income,
              COALESCE(SUM(CASE WHEN t.kind = 'EXPENSE'  THEN t.amount END * %1$s), 0) AS expense,
              COALESCE(SUM(CASE WHEN t.kind = 'LOAN_IN'  THEN t.amount END * %1$s), 0) AS loans_in,
              COALESCE(SUM(CASE WHEN t.kind = 'LOAN_OUT' THEN t.amount END * %1$s), 0) AS loans_out,
              COALESCE(SUM(CASE WHEN t.kind = 'TRANSFER' THEN t.amount END * %1$s), 0) AS transfers,
              COUNT(*) AS entries
            FROM transactions t
            WHERE t.txn_date BETWEEN ? AND ?
            """.formatted(SIGN), p.sqlFrom(), p.sqlTo());
        BigDecimal income = (BigDecimal) totals.get("income");
        BigDecimal expense = (BigDecimal) totals.get("expense");
        totals.put("net", income.subtract(expense));

        List<Map<String, Object>> monthly = jdbc.queryForList("""
            SELECT to_char(date_trunc('month', t.txn_date), 'YYYY-MM') AS month,
                   COALESCE(SUM(CASE WHEN t.kind = 'INCOME'  THEN t.amount END * %1$s), 0) AS income,
                   COALESCE(SUM(CASE WHEN t.kind = 'EXPENSE' THEN t.amount END * %1$s), 0) AS expense
            FROM transactions t
            WHERE t.txn_date BETWEEN ? AND ? AND t.kind IN ('INCOME','EXPENSE')
            GROUP BY 1 ORDER BY 1
            """.formatted(SIGN), p.sqlFrom(), p.sqlTo());

        List<Map<String, Object>> byCategory = jdbc.queryForList("""
            SELECT t.kind, COALESCE(c.name, 'Uncategorised') AS category, SUM(t.amount * %1$s) AS total
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            WHERE t.txn_date BETWEEN ? AND ? AND t.kind IN ('INCOME','EXPENSE')
            GROUP BY t.kind, c.name
            HAVING SUM(t.amount * %1$s) <> 0
            ORDER BY t.kind DESC, total DESC
            """.formatted(SIGN), p.sqlFrom(), p.sqlTo());

        List<Map<String, Object>> byFund = jdbc.queryForList("""
            SELECT f.name AS fund, f.restricted,
                   COALESCE(SUM(CASE WHEN t.kind = 'INCOME'  THEN t.amount END * %1$s), 0) AS income,
                   COALESCE(SUM(CASE WHEN t.kind = 'EXPENSE' THEN t.amount END * %1$s), 0) AS expense
            FROM transactions t
            JOIN funds f ON f.id = t.fund_id
            WHERE t.txn_date BETWEEN ? AND ? AND t.kind IN ('INCOME','EXPENSE')
            GROUP BY f.name, f.restricted
            ORDER BY f.name
            """.formatted(SIGN), p.sqlFrom(), p.sqlTo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.from());
        out.put("to", p.to());
        out.put("totals", totals);
        out.put("monthly", monthly);
        out.put("byCategory", byCategory);
        out.put("byFund", byFund);
        out.put("balances", balances(p.to()));
        out.put("loans", loans(p.to()));
        return out;
    }

    /** Balance of each active account at the end of the given day. */
    public List<Map<String, Object>> balances(LocalDate asAt) {
        return jdbc.queryForList("""
            SELECT a.id, a.name, a.type, COALESCE(SUM(m.delta), 0) AS balance
            FROM accounts a
            LEFT JOIN (
                SELECT t.account_id AS acc,
                       (CASE WHEN t.kind IN ('INCOME','LOAN_IN') THEN t.amount ELSE -t.amount END) * %1$s AS delta
                FROM transactions t WHERE t.txn_date <= ?
                UNION ALL
                SELECT t.to_account_id, t.amount * %1$s
                FROM transactions t WHERE t.kind = 'TRANSFER' AND t.txn_date <= ?
            ) m ON m.acc = a.id
            WHERE a.active
            GROUP BY a.id, a.name, a.type
            ORDER BY a.name
            """.formatted(SIGN), Date.valueOf(asAt), Date.valueOf(asAt));
    }

    /** What is owed to each lender at the end of the given day (loans in minus loans repaid). */
    public List<Map<String, Object>> loans(LocalDate asAt) {
        return jdbc.queryForList("""
            SELECT t.counterparty AS lender,
                   SUM((CASE WHEN t.kind = 'LOAN_IN' THEN t.amount ELSE -t.amount END) * %1$s) AS owed
            FROM transactions t
            WHERE t.kind IN ('LOAN_IN','LOAN_OUT') AND t.txn_date <= ?
            GROUP BY t.counterparty
            HAVING SUM((CASE WHEN t.kind = 'LOAN_IN' THEN t.amount ELSE -t.amount END) * %1$s) <> 0
            ORDER BY t.counterparty
            """.formatted(SIGN), Date.valueOf(asAt));
    }

    /** Every entry in the period with names instead of ids, oldest first (for exports). */
    public List<Map<String, Object>> entries(Period p) {
        return jdbc.queryForList("""
            SELECT t.id, t.txn_date, t.kind, t.amount, a.name AS account, ta.name AS to_account,
                   f.name AS fund, c.name AS category, t.channel, t.counterparty, t.reference,
                   t.reversal_of_id, t.created_by
            FROM transactions t
            JOIN accounts a ON a.id = t.account_id
            LEFT JOIN accounts ta ON ta.id = t.to_account_id
            JOIN funds f ON f.id = t.fund_id
            LEFT JOIN categories c ON c.id = t.category_id
            WHERE t.txn_date BETWEEN ? AND ?
            ORDER BY t.txn_date, t.id
            """, p.sqlFrom(), p.sqlTo());
    }

    public String organisationName() {
        return jdbc.queryForObject("SELECT name FROM organisation WHERE id = 1", String.class);
    }
}

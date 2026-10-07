package za.co.rockmission.ledger.report;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ReportController {

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/organisation")
    public Map<String, Object> organisation() {
        return jdbc.queryForMap(
            "SELECT name, registration_number, registered_on, year_end_month, pbo_status FROM organisation WHERE id = 1");
    }

    @GetMapping("/financial-years")
    public List<Map<String, Object>> financialYears() {
        return jdbc.queryForList(
            "SELECT id, label, start_date, end_date, status FROM financial_years ORDER BY start_date DESC");
    }

    /**
     * Income and expenses for one financial year, by fund and category.
     * A reversal entry counts as negative, so reversed entries net to zero.
     */
    @GetMapping("/reports/year/{id}")
    public Map<String, Object> yearSummary(@PathVariable Long id) {
        List<Map<String, Object>> fy = jdbc.queryForList(
            "SELECT id, label, start_date, end_date, status FROM financial_years WHERE id = ?", id);
        if (fy.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT t.kind, f.name AS fund, COALESCE(c.name, 'Uncategorised') AS category,
                   SUM(CASE WHEN t.reversal_of_id IS NULL THEN t.amount ELSE -t.amount END) AS total
            FROM transactions t
            JOIN funds f ON f.id = t.fund_id
            LEFT JOIN categories c ON c.id = t.category_id
            JOIN financial_years y ON t.txn_date BETWEEN y.start_date AND y.end_date
            WHERE y.id = ? AND t.kind IN ('INCOME','EXPENSE')
            GROUP BY t.kind, f.name, c.name
            ORDER BY t.kind, f.name, category
            """, id);

        return Map.of("financialYear", fy.get(0), "lines", lines);
    }

    /** Current balance per account (bank, cash, and money held by a platform). Reversals count as negative. */
    @GetMapping("/reports/balances")
    public List<Map<String, Object>> balances() {
        return jdbc.queryForList("""
            SELECT a.id, a.name, a.type, COALESCE(SUM(m.delta), 0) AS balance
            FROM accounts a
            LEFT JOIN (
                SELECT account_id AS acc,
                       (CASE WHEN kind IN ('INCOME','LOAN_IN') THEN amount ELSE -amount END)
                         * (CASE WHEN reversal_of_id IS NULL THEN 1 ELSE -1 END) AS delta
                FROM transactions
                UNION ALL
                SELECT to_account_id, amount * (CASE WHEN reversal_of_id IS NULL THEN 1 ELSE -1 END)
                FROM transactions WHERE kind = 'TRANSFER'
            ) m ON m.acc = a.id
            WHERE a.active
            GROUP BY a.id, a.name, a.type
            ORDER BY a.name
            """);
    }

    /** What the ministry currently owes each lender (loans in minus loans repaid). Reversals count as negative. */
    @GetMapping("/reports/director-loans")
    public List<Map<String, Object>> directorLoans() {
        return jdbc.queryForList("""
            SELECT counterparty AS lender,
                   SUM(CASE WHEN kind = 'LOAN_IN' THEN amount ELSE -amount END
                       * (CASE WHEN reversal_of_id IS NULL THEN 1 ELSE -1 END)) AS owed
            FROM transactions
            WHERE kind IN ('LOAN_IN','LOAN_OUT')
            GROUP BY counterparty
            ORDER BY counterparty
            """);
    }
}

package za.co.rockmission.ledger.statement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import za.co.rockmission.ledger.transaction.Transaction;
import za.co.rockmission.ledger.transaction.TransactionRepository;

@RestController
@RequestMapping("/api")
public class StatementController {

    public record ImportResult(Long statementId, String layout, LocalDate from, LocalDate to,
                               BigDecimal opening, BigDecimal closing, int lines, int newLines,
                               int duplicates, List<String> warnings) {}

    public record PostLine(String kind, Long fundId, Long categoryId, String counterparty,
                           String reference, String notes, Long otherAccountId) {}

    private static final Pattern BANK_FEE_LINE =
        Pattern.compile("(?i)^(monthly (service|account admin) fee|notification fee).*");

    private final JdbcTemplate jdbc;
    private final TransactionRepository txns;

    public StatementController(JdbcTemplate jdbc, TransactionRepository txns) {
        this.jdbc = jdbc;
        this.txns = txns;
    }

    @PostMapping(value = "/bank-statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ImportResult upload(@RequestParam("file") MultipartFile file, Principal who) throws Exception {
        byte[] pdf = file.getBytes();
        String name = file.getOriginalFilename() == null ? "statement.pdf" : file.getOriginalFilename();
        if (pdf.length == 0 || !name.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload the statement as a PDF");
        }
        String sha = sha256(pdf);
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM bank_statements WHERE sha256 = ?", Integer.class, sha);
        if (seen != null && seen > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This statement was already imported");
        }
        Long accountId = jdbc.queryForObject(
            "SELECT id FROM accounts WHERE type = 'BANK' AND active ORDER BY id LIMIT 1", Long.class);

        StatementParser.Parsed parsed = StatementParser.parse(PdfText.extract(pdf));
        if (parsed.opening() == null || parsed.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Could not read this statement: " + String.join(" ", parsed.warnings()));
        }
        List<String> warnings = new ArrayList<>(parsed.warnings());

        // Chain check: this statement should start where an imported one ended.
        List<BigDecimal> closings = jdbc.queryForList(
            "SELECT closing_balance FROM bank_statements WHERE account_id = ?", BigDecimal.class, accountId);
        List<BigDecimal> openings = jdbc.queryForList(
            "SELECT opening_balance FROM bank_statements WHERE account_id = ?", BigDecimal.class, accountId);
        boolean followsOne = closings.stream().anyMatch(c -> c.compareTo(parsed.opening()) == 0);
        boolean precedesOne = openings.stream().anyMatch(o -> o.compareTo(parsed.closing()) == 0);
        if (!closings.isEmpty() && !followsOne && !precedesOne) {
            warnings.add("This statement does not join onto any imported statement (opening " + parsed.opening()
                + ", closing " + parsed.closing() + "). A month may be missing.");
        }

        Long statementId = jdbc.queryForObject("""
            INSERT INTO bank_statements (account_id, file_name, sha256, layout, period_from, period_to,
                opening_balance, closing_balance, line_count, pdf, imported_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """, Long.class, accountId, name, sha, parsed.layout(), parsed.from(), parsed.to(),
            parsed.opening(), parsed.closing(), parsed.lines().size(), pdf, who.getName());

        int inserted = 0;
        for (StatementParser.Line l : parsed.lines()) {
            String hash = sha256((accountId + "|" + l.txnDate() + "|" + l.amount() + "|" + l.fee() + "|"
                + l.balanceAfter() + "|" + l.description()).getBytes(StandardCharsets.UTF_8));
            inserted += jdbc.update("""
                INSERT INTO bank_lines (statement_id, account_id, post_date, txn_date, description, amount, fee,
                    balance_after, line_hash)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (line_hash) DO NOTHING
                """, statementId, accountId, l.postDate(), l.txnDate(), l.description(), l.amount(), l.fee(),
                l.balanceAfter(), hash);
        }
        jdbc.update("INSERT INTO audit_log (actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
            who.getName(), "IMPORT", "bank_statement", statementId, name + ": " + inserted + " new lines");
        return new ImportResult(statementId, parsed.layout(), parsed.from(), parsed.to(), parsed.opening(),
            parsed.closing(), parsed.lines().size(), inserted, parsed.lines().size() - inserted, warnings);
    }

    @GetMapping("/bank-statements")
    public List<Map<String, Object>> statements() {
        return jdbc.queryForList("""
            SELECT id, file_name, layout, period_from, period_to, opening_balance, closing_balance, line_count, imported_at
            FROM bank_statements ORDER BY period_from, id
            """);
    }

    @GetMapping("/bank-lines")
    public List<Map<String, Object>> lines(@RequestParam(defaultValue = "unposted") String status) {
        String where = switch (status) {
            case "posted" -> "WHERE transaction_id IS NOT NULL";
            case "all" -> "";
            default -> "WHERE transaction_id IS NULL";
        };
        return jdbc.queryForList("SELECT id, txn_date, description, amount, fee, balance_after, transaction_id "
            + "FROM bank_lines " + where + " ORDER BY txn_date, id");
    }

    @PostMapping("/bank-lines/{id}/post")
    @Transactional
    public Map<String, Object> post(@PathVariable Long id, @RequestBody PostLine in, Principal who) {
        return postOne(id, in, who.getName());
    }

    /** Posts every unposted bank-fee line (monthly fee, notification fee) as "Bank charges" in one go. */
    @PostMapping("/bank-lines/post-fees")
    @Transactional
    public Map<String, Object> postFees(Principal who) {
        Long general = jdbc.queryForObject("SELECT id FROM funds WHERE name = 'General'", Long.class);
        Long charges = chargesCategory();
        int n = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, description FROM bank_lines WHERE transaction_id IS NULL AND amount < 0")) {
            if (BANK_FEE_LINE.matcher((String) row.get("description")).matches()) {
                postOne(((Number) row.get("id")).longValue(),
                    new PostLine("EXPENSE", general, charges, "Capitec", null, null, null), who.getName());
                n++;
            }
        }
        return Map.of("posted", n);
    }

    private Long chargesCategory() {
        return jdbc.queryForObject(
            "SELECT id FROM categories WHERE name = 'Bank charges' AND kind = 'EXPENSE'", Long.class);
    }

    private Map<String, Object> postOne(Long id, PostLine in, String actor) {
        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT * FROM bank_lines WHERE id = ? FOR UPDATE", id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> line = found.get(0);
        if (line.get("transaction_id") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This line is already posted");
        }
        BigDecimal amount = (BigDecimal) line.get("amount");
        BigDecimal fee = (BigDecimal) line.get("fee");
        Long bankAccount = ((Number) line.get("account_id")).longValue();
        boolean inbound = amount.signum() > 0;
        String kind = in.kind() == null ? "" : in.kind();

        boolean okKind = inbound
            ? List.of("INCOME", "LOAN_IN", "TRANSFER").contains(kind)
            : List.of("EXPENSE", "LOAN_OUT", "TRANSFER").contains(kind);
        if (!okKind) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                inbound ? "Money in must be income, a loan in, or a transfer"
                        : "Money out must be an expense, a loan repaid, or a transfer");
        }
        if (in.fundId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a fund");

        Transaction t = new Transaction();
        t.setTxnDate(((java.sql.Date) line.get("txn_date")).toLocalDate());
        t.setKind(kind);
        t.setAmount(amount.abs());
        t.setFundId(in.fundId());
        t.setCounterparty(in.counterparty());
        t.setReference(in.reference() != null ? in.reference() : "Bank line #" + id);
        t.setNotes(in.notes());
        t.setCreatedBy(actor);

        if (kind.equals("TRANSFER")) {
            if (in.otherAccountId() == null || in.otherAccountId().equals(bankAccount)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the other account for the transfer");
            }
            t.setAccountId(inbound ? in.otherAccountId() : bankAccount);
            t.setToAccountId(inbound ? bankAccount : in.otherAccountId());
        } else {
            t.setAccountId(bankAccount);
            if (kind.startsWith("LOAN_")) {
                if (in.counterparty() == null || in.counterparty().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A loan needs the lender's name");
                }
            } else {
                if (in.categoryId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a category");
                }
                String catKind = jdbc.queryForObject("SELECT kind FROM categories WHERE id = ?", String.class, in.categoryId());
                if (!kind.equals(catKind)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That category is for " + catKind.toLowerCase());
                }
                t.setCategoryId(in.categoryId());
            }
        }
        Transaction saved = txns.save(t);

        Long feeTxnId = null;
        if (fee.signum() != 0) {
            Transaction f = new Transaction();
            f.setTxnDate(saved.getTxnDate());
            f.setKind("EXPENSE");
            f.setAmount(fee.abs());
            f.setAccountId(bankAccount);
            f.setFundId(jdbc.queryForObject("SELECT id FROM funds WHERE name = 'General'", Long.class));
            f.setCategoryId(chargesCategory());
            f.setCounterparty("Capitec");
            f.setReference("Bank fee on bank line #" + id);
            f.setCreatedBy(actor);
            feeTxnId = txns.save(f).getId();
        }
        jdbc.update("UPDATE bank_lines SET transaction_id = ?, fee_transaction_id = ?, posted_by = ?, posted_at = now() WHERE id = ?",
            saved.getId(), feeTxnId, actor, id);
        jdbc.update("INSERT INTO audit_log (actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
            actor, "POST_BANK_LINE", "bank_line", id, "transaction " + saved.getId());
        return Map.of("transactionId", saved.getId());
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

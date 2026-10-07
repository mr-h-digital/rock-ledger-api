package za.co.rockmission.ledger.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import za.co.rockmission.ledger.storage.FileStore;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private static final int MAX_FILE_NAME_LENGTH = 300;

    public record NewTransaction(
            @NotNull LocalDate txnDate,
            @NotNull @Pattern(regexp = "INCOME|EXPENSE|TRANSFER|LOAN_IN|LOAN_OUT") String kind,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull Long accountId,
            @NotNull Long fundId,
            Long categoryId,
            @Pattern(regexp = "BANK_DEPOSIT|CASH|BACKABUDDY|OTHER") String channel,
            Long toAccountId,
            @Size(max = 200) String counterparty,
            @Size(max = 200) String reference,
            String notes) {}

    private final TransactionRepository repo;
    private final JdbcTemplate jdbc;
    private final FileStore files;

    public TransactionController(TransactionRepository repo, JdbcTemplate jdbc, FileStore files) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.files = files;
    }

    @GetMapping
    public List<Transaction> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(90);
        return repo.findByTxnDateBetweenOrderByTxnDateDescIdDesc(start, end);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction create(@Valid @RequestBody NewTransaction in, Principal who) {
        boolean transfer = "TRANSFER".equals(in.kind());
        if (transfer && (in.toAccountId() == null || in.toAccountId().equals(in.accountId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A transfer needs a different destination account");
        }
        if (!transfer && in.toAccountId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only transfers have a destination account");
        }
        boolean loan = in.kind().startsWith("LOAN_");
        if (loan && (in.counterparty() == null || in.counterparty().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A loan entry needs the lender's name");
        }
        Transaction t = new Transaction();
        t.setChannel(in.channel() != null ? in.channel() : "BANK_DEPOSIT");
        t.setToAccountId(in.toAccountId());
        t.setTxnDate(in.txnDate());
        t.setKind(in.kind());
        t.setAmount(in.amount());
        t.setAccountId(in.accountId());
        t.setFundId(in.fundId());
        t.setCategoryId(in.categoryId());
        t.setCounterparty(in.counterparty());
        t.setReference(in.reference());
        t.setNotes(in.notes());
        t.setCreatedBy(who.getName());
        return repo.save(t);
    }

    @GetMapping("/{id}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable Long id) {
        requireTransaction(id);
        return jdbc.queryForList("""
            SELECT id, file_name, content_type, uploaded_by, uploaded_at
            FROM attachments WHERE transaction_id = ? ORDER BY uploaded_at, id
            """, id);
    }

    @GetMapping("/{id}/document-history")
    public List<Map<String, Object>> documentHistory(@PathVariable Long id) {
        requireTransaction(id);
        return jdbc.queryForList("""
            SELECT actor, action, detail, at
            FROM audit_log
            WHERE entity = 'transaction' AND entity_id = ?
                AND action IN ('ATTACH_DOCUMENT', 'DELETE_DOCUMENT')
            ORDER BY at, id
            """, id);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> uploadAttachment(
            @PathVariable Long id, @RequestParam("file") MultipartFile file, Principal who) {
        requireTransaction(id);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file to upload");
        }
        if (!files.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Document storage is not configured");
        }

        String name = safeFileName(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        String key = "transaction-documents/" + id + "/" + UUID.randomUUID();
        try {
            files.put(key, file.getBytes(), contentType);
            Long attachmentId = jdbc.queryForObject("""
                INSERT INTO attachments (transaction_id, object_key, file_name, content_type, uploaded_by)
                VALUES (?,?,?,?,?) RETURNING id
                """, Long.class, id, key, name, contentType, who.getName());
            jdbc.update("INSERT INTO audit_log (actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
                who.getName(), "ATTACH_DOCUMENT", "transaction", id, name);
            return Map.of("id", attachmentId, "fileName", name);
        } catch (Exception failure) {
            try {
                files.delete(key);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new IllegalStateException("Could not store the uploaded document", failure);
        }
    }

    private void requireTransaction(Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String safeFileName(String originalName) {
        String name = originalName == null ? "document" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "document";
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(name.length() - MAX_FILE_NAME_LENGTH);
    }

    /** Ledger entries are never edited or deleted: a correction is a reversal plus a new entry. */
    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction reverse(@PathVariable Long id, Principal who) {
        Transaction original = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (original.getReversalOfId() != null || repo.existsByReversalOfId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already reversed (or is a reversal)");
        }
        Transaction r = new Transaction();
        r.setTxnDate(LocalDate.now());
        r.setKind(original.getKind());
        r.setAmount(original.getAmount());
        r.setAccountId(original.getAccountId());
        r.setFundId(original.getFundId());
        r.setCategoryId(original.getCategoryId());
        r.setChannel(original.getChannel());
        r.setToAccountId(original.getToAccountId());
        r.setCounterparty(original.getCounterparty());
        r.setReference("REVERSAL of #" + id);
        r.setReversalOfId(id);
        r.setCreatedBy(who.getName());
        return repo.save(r);
    }
}

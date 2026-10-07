package za.co.rockmission.ledger.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

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

    public TransactionController(TransactionRepository repo) {
        this.repo = repo;
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

package za.co.rockmission.ledger.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(nullable = false, length = 10)
    private String kind; // INCOME | EXPENSE | TRANSFER

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, length = 20)
    private String channel = "BANK_DEPOSIT";

    @Column(name = "to_account_id")
    private Long toAccountId;

    private String counterparty;
    private String reference;
    private String notes;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public LocalDate getTxnDate() { return txnDate; }
    public void setTxnDate(LocalDate v) { this.txnDate = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long v) { this.accountId = v; }
    public Long getFundId() { return fundId; }
    public void setFundId(Long v) { this.fundId = v; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long v) { this.categoryId = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public Long getToAccountId() { return toAccountId; }
    public void setToAccountId(Long v) { this.toAccountId = v; }
    public String getCounterparty() { return counterparty; }
    public void setCounterparty(String v) { this.counterparty = v; }
    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Long getReversalOfId() { return reversalOfId; }
    public void setReversalOfId(Long v) { this.reversalOfId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}

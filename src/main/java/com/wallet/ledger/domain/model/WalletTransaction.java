package com.wallet.ledger.domain.model;

import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.enums.TransactionStatus;
import com.wallet.ledger.domain.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private TransactionReason reason;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    /** Counterparty leg of a player-to-player transfer; null otherwise. */
    @Column(name = "related_transaction_id")
    private Long relatedTransactionId;

    /** Original transaction reversed by this refund leg; null otherwise. */
    @Column(name = "original_transaction_id")
    private Long originalTransactionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WalletTransaction() {
        // for JPA
    }

    public WalletTransaction(Wallet wallet,
                             TransactionType type,
                             TransactionStatus status,
                             TransactionReason reason,
                             String referenceId,
                             long amount,
                             String currencyCode,
                             String idempotencyKey,
                             String requestHash,
                             long balanceAfter) {
        this(wallet, type, status, reason, referenceId, amount, currencyCode,
                idempotencyKey, requestHash, balanceAfter, null, null);
    }

    /** Full constructor for transfer legs (relatedTransactionId) and refunds (originalTransactionId). */
    public WalletTransaction(Wallet wallet,
                             TransactionType type,
                             TransactionStatus status,
                             TransactionReason reason,
                             String referenceId,
                             long amount,
                             String currencyCode,
                             String idempotencyKey,
                             String requestHash,
                             long balanceAfter,
                             Long relatedTransactionId,
                             Long originalTransactionId) {
        this.wallet = wallet;
        this.type = type;
        this.status = status;
        this.reason = reason;
        this.referenceId = referenceId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.balanceAfter = balanceAfter;
        this.relatedTransactionId = relatedTransactionId;
        this.originalTransactionId = originalTransactionId;
    }

    /** Used to cross-link the two legs of a transfer after both ids are known. */
    public void linkCounterparty(Long counterpartyTransactionId) {
        this.relatedTransactionId = counterpartyTransactionId;
    }

    public Long getId() {
        return id;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public TransactionReason getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Long getRelatedTransactionId() {
        return relatedTransactionId;
    }

    public Long getOriginalTransactionId() {
        return originalTransactionId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

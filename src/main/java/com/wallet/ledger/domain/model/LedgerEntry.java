package com.wallet.ledger.domain.model;

import com.wallet.ledger.domain.enums.EntryDirection;
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

/** Immutable, append-only ledger line. Never updated or deleted after insert. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private WalletTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private EntryDirection direction;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    public LedgerEntry(WalletTransaction transaction,
                       Wallet wallet,
                       EntryDirection direction,
                       long amount,
                       long balanceAfter) {
        this.transaction = transaction;
        this.wallet = wallet;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public Long getId() {
        return id;
    }

    public WalletTransaction getTransaction() {
        return transaction;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

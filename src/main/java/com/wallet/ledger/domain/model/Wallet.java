package com.wallet.ledger.domain.model;

import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /** Cached balance in minor units; must always equal the sum of ledger entries. */
    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Wallet() {
        // for JPA
    }

    public Wallet(Player player, String currencyCode) {
        this.player = player;
        this.currencyCode = currencyCode;
        this.balance = 0L;
    }

    public void credit(long amountMinor) {
        if (amountMinor <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "amount must be positive");
        }
        this.balance += amountMinor;
    }

    public void debit(long amountMinor) {
        if (amountMinor <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "amount must be positive");
        }
        if (this.balance < amountMinor) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Wallet balance %d is less than requested %d".formatted(balance, amountMinor));
        }
        this.balance -= amountMinor;
    }

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

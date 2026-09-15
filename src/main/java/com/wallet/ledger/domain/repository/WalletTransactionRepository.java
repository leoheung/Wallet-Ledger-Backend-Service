package com.wallet.ledger.domain.repository;

import com.wallet.ledger.domain.model.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);

    long countByWalletId(Long walletId);

    Page<WalletTransaction> findByWalletIdOrderByCreatedAtDescIdDesc(Long walletId, Pageable pageable);
}

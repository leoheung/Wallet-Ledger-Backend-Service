package com.wallet.ledger.domain.repository;

import com.wallet.ledger.domain.enums.EntryDirection;
import com.wallet.ledger.domain.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByWalletId(Long walletId);

    /** Signed sum: credits minus debits — the independently recomputed balance. */
    @Query("""
            select coalesce(sum(case when e.direction = :creditDirection
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e
            where e.wallet.id = :walletId
            """)
    long sumSignedAmountByWalletId(@Param("walletId") Long walletId,
                                   @Param("creditDirection") EntryDirection creditDirection);
}

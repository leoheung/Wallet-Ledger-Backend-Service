package com.wallet.ledger.domain.repository;

import com.wallet.ledger.domain.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByPlayerId(Long playerId);

    /**
     * Row-level write lock (SELECT ... FOR UPDATE). All concurrent money moves
     * on the same wallet serialise here, which makes both the balance check and
     * the idempotency replay safe under contention.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.player.id = :playerId")
    Optional<Wallet> findByPlayerIdForUpdate(@Param("playerId") Long playerId);
}

package com.wallet.ledger.domain.repository;

import com.wallet.ledger.domain.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {
}

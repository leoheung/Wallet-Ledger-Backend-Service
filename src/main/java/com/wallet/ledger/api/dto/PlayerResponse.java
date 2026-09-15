package com.wallet.ledger.api.dto;

import com.wallet.ledger.domain.model.Player;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record PlayerResponse(Long playerId,
                             String displayName,
                             OffsetDateTime createdAt) {

    public static PlayerResponse from(Player player) {
        return new PlayerResponse(player.getId(), player.getDisplayName(),
                player.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}

package com.wallet.ledger.api.controller;

import com.wallet.ledger.api.dto.CreatePlayerRequest;
import com.wallet.ledger.api.dto.PlayerResponse;
import com.wallet.ledger.application.WalletAppService;
import com.wallet.ledger.domain.model.Player;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/players")
@Tag(name = "Players", description = "Player onboarding")
public class PlayerController {

    private final WalletAppService walletAppService;

    public PlayerController(WalletAppService walletAppService) {
        this.walletAppService = walletAppService;
    }

    @PostMapping
    @Operation(summary = "Create a player together with an empty wallet")
    public ResponseEntity<PlayerResponse> createPlayer(@Valid @RequestBody CreatePlayerRequest request,
                                                       UriComponentsBuilder uriBuilder) {
        Player player = walletAppService.createPlayer(request.displayName(), request.currencyCode());
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/players/{id}").build(player.getId()))
                .body(PlayerResponse.from(player));
    }
}

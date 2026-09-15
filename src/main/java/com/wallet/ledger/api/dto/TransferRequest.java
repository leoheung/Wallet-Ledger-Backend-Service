package com.wallet.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request body for a player-to-player currency transfer")
public record TransferRequest(

        @Schema(description = "Recipient player id (must differ from the sender)",
                example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long toPlayerId,

        @Schema(description = "Positive amount in major currency units", example = "12.50",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than zero")
        BigDecimal amount,

        @Schema(example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be a 3-letter uppercase ISO 4217 code")
        String currencyCode,

        @Schema(description = "Optional external business reference (trade id, game session, ...)",
                example = "trade-2026-0007")
        @Size(max = 100)
        String referenceId) {
}

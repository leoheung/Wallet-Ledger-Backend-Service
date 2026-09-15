package com.wallet.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a player and their wallet")
public record CreatePlayerRequest(

        @Schema(example = "Alice", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100)
        String displayName,

        @Schema(description = "ISO 4217 currency code; defaults to USD", example = "USD")
        @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be a 3-letter uppercase ISO 4217 code")
        String currencyCode) {
}

package com.wallet.ledger.api.dto;

import com.wallet.ledger.domain.enums.TransactionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request body for a wallet credit or debit")
public record WalletOperationRequest(

        @Schema(description = "Positive amount in major currency units", example = "12.50",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than zero")
        BigDecimal amount,

        @Schema(example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "currencyCode must be a 3-letter uppercase ISO 4217 code")
        String currencyCode,

        @Schema(example = "MISSION_REWARD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        TransactionReason reason,

        @Schema(description = "Optional external business reference (mission id, order id, ...)",
                example = "mission-2026-0007")
        @Size(max = 100)
        String referenceId) {
}

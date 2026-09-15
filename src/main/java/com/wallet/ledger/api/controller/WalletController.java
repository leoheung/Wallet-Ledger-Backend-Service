package com.wallet.ledger.api.controller;

import com.wallet.ledger.api.dto.PagedResponse;
import com.wallet.ledger.api.dto.TransactionResponse;
import com.wallet.ledger.api.dto.WalletOperationRequest;
import com.wallet.ledger.api.dto.WalletOperationResponse;
import com.wallet.ledger.api.dto.WalletResponse;
import com.wallet.ledger.application.BalanceView;
import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.application.WalletAppService;
import com.wallet.ledger.application.WalletQueryService;
import com.wallet.ledger.domain.model.WalletTransaction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/players/{playerId}/wallet")
@Validated
@Tag(name = "Wallet", description = "Balance, credits, debits and transaction history")
public class WalletController {

    private final WalletAppService walletAppService;
    private final WalletQueryService walletQueryService;

    public WalletController(WalletAppService walletAppService,
                            WalletQueryService walletQueryService) {
        this.walletAppService = walletAppService;
        this.walletQueryService = walletQueryService;
    }

    @GetMapping
    @Operation(summary = "Get the current wallet balance (Redis cache-aside)")
    public WalletResponse getWallet(@PathVariable Long playerId) {
        BalanceView view = walletQueryService.getBalanceView(playerId);
        return WalletResponse.from(view);
    }

    @PostMapping("/credit")
    @Operation(summary = "Credit the wallet",
            parameters = @Parameter(name = "Idempotency-Key", required = true,
                    description = "Client-generated unique key; retries with the same key apply only once"))
    public WalletOperationResponse credit(
            @PathVariable Long playerId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WalletOperationRequest request) {
        LedgerOperationResult result = walletAppService.credit(
                playerId, request.currencyCode(), request.amount(), request.reason(),
                request.referenceId(), idempotencyKey);
        return WalletOperationResponse.from(result);
    }

    @PostMapping("/debit")
    @Operation(summary = "Debit the wallet; returns 422 when the balance is insufficient",
            parameters = @Parameter(name = "Idempotency-Key", required = true,
                    description = "Client-generated unique key; retries with the same key apply only once"))
    public WalletOperationResponse debit(
            @PathVariable Long playerId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WalletOperationRequest request) {
        LedgerOperationResult result = walletAppService.debit(
                playerId, request.currencyCode(), request.amount(), request.reason(),
                request.referenceId(), idempotencyKey);
        return WalletOperationResponse.from(result);
    }

    @GetMapping("/transactions")
    @Operation(summary = "Paginated transaction history, newest first")
    public PagedResponse<TransactionResponse> history(
            @PathVariable Long playerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<WalletTransaction> result =
                walletAppService.history(playerId, PageRequest.of(page, size));
        return PagedResponse.from(result, TransactionResponse::from);
    }
}

package com.wallet.ledger.api.controller;

import com.wallet.ledger.api.dto.ReconciliationResponse;
import com.wallet.ledger.application.ReconciliationReport;
import com.wallet.ledger.application.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Operational endpoints")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Verify cached balance matches the full ledger history")
    public ReconciliationResponse reconcile(@RequestParam Long playerId) {
        ReconciliationReport report = reconciliationService.reconcile(playerId);
        return ReconciliationResponse.from(report);
    }
}

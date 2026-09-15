package com.wallet.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API behaviour on H2 (PostgreSQL mode). Concurrency correctness
 * itself is proven against real PostgreSQL in src/test/.../it/*IT.java.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(statements = {
        "DELETE FROM ledger_entries",
        "DELETE FROM wallet_transactions",
        "DELETE FROM wallets",
        "DELETE FROM players"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WalletApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private long ledgerEntryCount;

    @BeforeEach
    void rememberLedgerCount() {
        ledgerEntryCount = ledgerEntryRepository.count();
    }

    // ------------------------------------------------------------- onboarding

    @Test
    void createPlayer_defaultsToUsd_withZeroBalanceWallet() throws Exception {
        long playerId = createPlayer("Alice", null);

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    // ------------------------------------------------------------- happy paths

    @Test
    void credit_increasesBalance_andRecordsHistory() throws Exception {
        long playerId = createPlayer("Alice", "USD");

        credit(playerId, "reward-1", body("12.50", "USD", "MISSION_REWARD", "mission-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.referenceId").value("mission-7"));

        assertBalance(playerId, "12.50");

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(12.50))
                .andExpect(jsonPath("$.content[0].reason").value("MISSION_REWARD"))
                .andExpect(jsonPath("$.content[0].referenceId").value("mission-7"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void debit_reducesBalance_andReportsBalanceAfter() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        credit(playerId, "seed", body("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/debit", playerId)
                        .header("Idempotency-Key", "buy-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("30.00", "USD", "PURCHASE", "order-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.balanceAfter").value(70.00));

        assertBalance(playerId, "70.00");
    }

    // --------------------------------------------------------------- safety

    @Test
    void debit_withInsufficientFunds_returns422_andLeavesNoPartialUpdate() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        credit(playerId, "seed", body("10.00", "USD", "ADMIN_ADJUSTMENT", null));

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/debit", playerId)
                        .header("Idempotency-Key", "too-big")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("20.00", "USD", "PURCHASE", "order-2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.status").value(422));

        assertBalance(playerId, "10.00");

        // Only the seed credit exists — the failed debit wrote nothing.
        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId))
                .andExpect(jsonPath("$.totalElements").value(1));
        org.assertj.core.api.Assertions.assertThat(ledgerEntryRepository.count())
                .isEqualTo(ledgerEntryCount + 1);
    }

    @Test
    void credit_negativeAmount_returns400_withFieldError() throws Exception {
        long playerId = createPlayer("Alice", "USD");

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                        .header("Idempotency-Key", "neg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("-5.00", "USD", "OTHER", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    void credit_withoutIdempotencyKey_returns400() throws Exception {
        long playerId = createPlayer("Alice", "USD");

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5.00", "USD", "OTHER", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void credit_unknownPlayer_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/players/999999/wallet/credit")
                        .header("Idempotency-Key", "ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5.00", "USD", "OTHER", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void unknownCurrencyCode_returns400() throws Exception {
        long playerId = createPlayer("Alice", "USD");

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                        .header("Idempotency-Key", "bad-ccy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5.00", "ZZZ", "OTHER", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void currencyNotMatchingWallet_returns422() throws Exception {
        long playerId = createPlayer("Alice", "EUR");

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                        .header("Idempotency-Key", "wrong-ccy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5.00", "USD", "OTHER", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        assertBalance(playerId, "0.00");
    }

    // ---------------------------------------------------------- idempotency

    @Test
    void sameIdempotencyKey_appliesOnce_andSecondCallIsMarkedReplayed() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        String payload = body("10.00", "USD", "MISSION_REWARD", "mission-1");

        MvcResult first = credit(playerId, "idem-1", payload).andReturn();
        long firstTxnId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("transactionId").asLong();

        MvcResult retry = credit(playerId, "idem-1", payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();
        long retryTxnId = objectMapper.readTree(retry.getResponse().getContentAsString())
                .get("transactionId").asLong();

        org.assertj.core.api.Assertions.assertThat(retryTxnId).isEqualTo(firstTxnId);
        assertBalance(playerId, "10.00");

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayload_returns409() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        credit(playerId, "idem-conflict", body("10.00", "USD", "MISSION_REWARD", "m-1"));

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                        .header("Idempotency-Key", "idem-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("99.00", "USD", "MISSION_REWARD", "m-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertBalance(playerId, "10.00");
    }

    // ------------------------------------------------- pagination & recon

    @Test
    void transactionHistory_isPaginated_newestFirst() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        credit(playerId, "p1", body("1.00", "USD", "OTHER", null));
        credit(playerId, "p2", body("2.00", "USD", "OTHER", null));
        credit(playerId, "p3", body("3.00", "USD", "OTHER", null));

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                // newest first: the 3.00 credit leads
                .andExpect(jsonPath("$.content[0].amount").value(3.00));

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId)
                        .param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void pageSizeAboveMaximum_returns400() throws Exception {
        long playerId = createPlayer("Alice", "USD");

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", playerId)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void reconciliation_reportsBalanceMatchesLedgerHistory() throws Exception {
        long playerId = createPlayer("Alice", "USD");
        credit(playerId, "r1", body("100.00", "USD", "MISSION_REWARD", null));
        credit(playerId, "r2", body("50.00", "USD", "MISSION_REWARD", null));
        MvcResult debitResult = mockMvc.perform(post("/api/v1/players/{playerId}/wallet/debit", playerId)
                        .header("Idempotency-Key", "r3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("30.00", "USD", "PURCHASE", null)))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(debitResult.getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(get("/api/v1/admin/reconciliation").param("playerId", String.valueOf(playerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.currentBalance").value(120.00))
                .andExpect(jsonPath("$.recomputedBalance").value(120.00))
                .andExpect(jsonPath("$.difference").value(0));
    }

    // ---------------------------------------------------------------- helpers

    private long createPlayer(String name, String currencyCode) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("displayName", name);
        if (currencyCode != null) {
            request.put("currencyCode", currencyCode);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("playerId").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions credit(long playerId,
                                                                      String idempotencyKey,
                                                                      String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private void assertBalance(long playerId, String expectedMajor) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/players/{playerId}/wallet", playerId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(node.get("balance").decimalValue())
                .isEqualByComparingTo(expectedMajor);
    }

    private String body(String amount, String currencyCode, String reason, String referenceId)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("amount", amount);
        request.put("currencyCode", currencyCode);
        request.put("reason", reason);
        if (referenceId != null) {
            request.put("referenceId", referenceId);
        }
        return objectMapper.writeValueAsString(request);
    }
}

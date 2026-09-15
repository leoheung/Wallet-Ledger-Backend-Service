package com.wallet.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import com.wallet.ledger.domain.repository.WalletTransactionRepository;
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
 * Phase 3 API behaviour on H2 (PostgreSQL mode): player-to-player transfers
 * and transaction refunds. Concurrency races are proven separately against
 * real PostgreSQL in src/test/.../it/*IT.java.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(statements = {
        "DELETE FROM ledger_entries",
        "DELETE FROM wallet_transactions",
        "DELETE FROM wallets",
        "DELETE FROM players"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TransferRefundApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private WalletTransactionRepository transactionRepository;

    private long ledgerEntryCount;

    @BeforeEach
    void rememberCounts() {
        ledgerEntryCount = ledgerEntryRepository.count();
    }

    // -------------------------------------------------------------- transfers

    @Test
    void transfer_movesFundsAtomically_andWritesTwoLinkedLegs() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        MvcResult result = transfer(alice, "transfer-1", transferBody(bob, "30.00", "USD", "trade-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderTransactionId").isNumber())
                .andExpect(jsonPath("$.receiverTransactionId").isNumber())
                .andExpect(jsonPath("$.senderBalanceAfter").value(70.00))
                .andExpect(jsonPath("$.receiverBalanceAfter").value(30.00))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long senderTxn = json.get("senderTransactionId").asLong();
        long receiverTxn = json.get("receiverTransactionId").asLong();
        org.assertj.core.api.Assertions.assertThat(senderTxn).isNotEqualTo(receiverTxn);

        assertBalance(alice, "70.00");
        assertBalance(bob, "30.00");

        // Each history shows its own leg, cross-linked to the counterparty leg.
        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", alice))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reason").value("PLAYER_TRANSFER"))
                .andExpect(jsonPath("$.content[0].type").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].relatedTransactionId").value(receiverTxn));
        mockMvc.perform(get("/api/v1/players/{playerId}/wallet/transactions", bob))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reason").value("PLAYER_TRANSFER"))
                .andExpect(jsonPath("$.content[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].relatedTransactionId").value(senderTxn));

        // Two immutable ledger entries for the two legs.
        org.assertj.core.api.Assertions.assertThat(ledgerEntryRepository.count())
                .as("seed + two transfer legs")
                .isEqualTo(ledgerEntryCount + 3);
    }

    @Test
    void transfer_toSelf_returns400() throws Exception {
        long alice = createPlayer("Alice", "USD");
        transfer(alice, "self", transferBody(alice, "10.00", "USD", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRANSFER_SAME_PLAYER"));
    }

    @Test
    void transfer_toUnknownPlayer_returns404() throws Exception {
        long alice = createPlayer("Alice", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        transfer(alice, "ghost", transferBody(999_999L, "10.00", "USD", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
        assertBalance(alice, "100.00");
    }

    @Test
    void transfer_betweenDifferentCurrencies_returns422() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "EUR");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        transfer(alice, "fx", transferBody(bob, "10.00", "USD", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        assertBalance(alice, "100.00");
        assertBalance(bob, "0.00");
    }

    @Test
    void transfer_withInsufficientFunds_returns422_andWritesNothing() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        credit(alice, "seed", walletBody("10.00", "USD", "ADMIN_ADJUSTMENT", null));

        transfer(alice, "too-big", transferBody(bob, "50.00", "USD", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalance(alice, "10.00");
        assertBalance(bob, "0.00");
        org.assertj.core.api.Assertions.assertThat(transactionRepository.count())
                .as("only the seed txn exists")
                .isEqualTo(1);
    }

    @Test
    void transfer_zeroAmount_returns400() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");

        transfer(alice, "zero", transferBody(bob, "0.00", "USD", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void transfer_withoutIdempotencyKey_returns400() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/transfer", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bob, "1.00", "USD", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void transfer_sameKey_replaysOnceWithSameLegIds() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));
        String payload = transferBody(bob, "30.00", "USD", "trade-1");

        MvcResult first = transfer(alice, "t-once", payload).andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult retry = transfer(alice, "t-once", payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();
        JsonNode retryJson = objectMapper.readTree(retry.getResponse().getContentAsString());

        org.assertj.core.api.Assertions.assertThat(retryJson.get("senderTransactionId").asLong())
                .isEqualTo(firstJson.get("senderTransactionId").asLong());
        org.assertj.core.api.Assertions.assertThat(retryJson.get("receiverTransactionId").asLong())
                .isEqualTo(firstJson.get("receiverTransactionId").asLong());
        assertBalance(alice, "70.00");
        assertBalance(bob, "30.00");
    }

    @Test
    void transfer_sameKeyDifferentAmount_returns409() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        transfer(alice, "t-conflict", transferBody(bob, "30.00", "USD", null)).andExpect(status().isOk());
        transfer(alice, "t-conflict", transferBody(bob, "40.00", "USD", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertBalance(alice, "70.00");
        assertBalance(bob, "30.00");
    }

    // ---------------------------------------------------------------- refunds

    @Test
    void refund_ofDebit_creditsMoneyBack() throws Exception {
        long alice = createPlayer("Alice", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));
        long purchaseId = debitAndGetTxnId(alice, "buy", "30.00");
        assertBalance(alice, "70.00");

        mockMvc.perform(refundRequest(alice, purchaseId, "refund-buy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.reason").value("REFUND"))
                .andExpect(jsonPath("$.amount").value(30.00))
                .andExpect(jsonPath("$.balanceAfter").value(100.00))
                .andExpect(jsonPath("$.originalTransactionId").value(purchaseId))
                .andExpect(jsonPath("$.replayed").value(false));

        assertBalance(alice, "100.00");
    }

    @Test
    void refund_ofCredit_debitsMoneyBack() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long rewardId = creditAndGetTxnId(alice, "reward", "50.00");
        assertBalance(alice, "50.00");

        mockMvc.perform(refundRequest(alice, rewardId, "refund-reward"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.reason").value("REFUND"))
                .andExpect(jsonPath("$.balanceAfter").value(0.00))
                .andExpect(jsonPath("$.originalTransactionId").value(rewardId));

        assertBalance(alice, "0.00");
    }

    @Test
    void refund_creditWhenBalanceWasSpent_returns422() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long rewardId = creditAndGetTxnId(alice, "reward", "100.00");
        debitAndGetTxnId(alice, "spend", "90.00");
        assertBalance(alice, "10.00");

        mockMvc.perform(refundRequest(alice, rewardId, "refund-late"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertBalance(alice, "10.00");
    }

    @Test
    void refund_unknownTransaction_returns404() throws Exception {
        long alice = createPlayer("Alice", "USD");

        mockMvc.perform(refundRequest(alice, 999_999L, "refund-ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void refund_anotherPlayersTransaction_returns404() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        long aliceTxn = creditAndGetTxnId(alice, "alice-reward", "10.00");

        // Bob must not even learn that Alice's transaction id exists.
        mockMvc.perform(refundRequest(bob, aliceTxn, "refund-snoop"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
        assertBalance(alice, "10.00");
    }

    @Test
    void refund_twice_secondReturns409() throws Exception {
        long alice = createPlayer("Alice", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));
        long purchaseId = debitAndGetTxnId(alice, "buy", "30.00");

        mockMvc.perform(refundRequest(alice, purchaseId, "refund-1"))
                .andExpect(status().isOk());
        mockMvc.perform(refundRequest(alice, purchaseId, "refund-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_REFUNDED"));

        assertBalance(alice, "100.00"); // seeded 100 - 30 + 30
    }

    @Test
    void refund_transferLeg_returns422_notRefundable() throws Exception {
        long alice = createPlayer("Alice", "USD");
        long bob = createPlayer("Bob", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));

        MvcResult transferResult = transfer(alice, "leg", transferBody(bob, "30.00", "USD", null))
                .andExpect(status().isOk()).andReturn();
        long senderLegId = objectMapper.readTree(transferResult.getResponse().getContentAsString())
                .get("senderTransactionId").asLong();

        mockMvc.perform(refundRequest(alice, senderLegId, "refund-leg"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_REFUNDABLE"));
        assertBalance(alice, "70.00");
        assertBalance(bob, "30.00");
    }

    @Test
    void refund_ofARefund_returns422() throws Exception {
        long alice = createPlayer("Alice", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));
        long purchaseId = debitAndGetTxnId(alice, "buy", "30.00");

        MvcResult refundResult = mockMvc.perform(refundRequest(alice, purchaseId, "refund-1"))
                .andExpect(status().isOk()).andReturn();
        long refundId = objectMapper.readTree(refundResult.getResponse().getContentAsString())
                .get("transactionId").asLong();

        mockMvc.perform(refundRequest(alice, refundId, "refund-chain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_REFUNDABLE"));
        assertBalance(alice, "100.00");
    }

    @Test
    void refund_sameKey_replaysOnce() throws Exception {
        long alice = createPlayer("Alice", "USD");
        credit(alice, "seed", walletBody("100.00", "USD", "ADMIN_ADJUSTMENT", null));
        long purchaseId = debitAndGetTxnId(alice, "buy", "30.00");

        MvcResult first = mockMvc.perform(refundRequest(alice, purchaseId, "r-once"))
                .andExpect(status().isOk()).andReturn();
        long firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("transactionId").asLong();

        MvcResult retry = mockMvc.perform(refundRequest(alice, purchaseId, "r-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();
        long retryId = objectMapper.readTree(retry.getResponse().getContentAsString())
                .get("transactionId").asLong();

        org.assertj.core.api.Assertions.assertThat(retryId).isEqualTo(firstId);
        assertBalance(alice, "100.00");
    }

    // ---------------------------------------------------------------- helpers

    private long createPlayer(String name, String currencyCode) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("displayName", name);
        request.put("currencyCode", currencyCode);
        MvcResult result = mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("playerId").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions credit(long playerId, String key, String payload)
            throws Exception {
        return mockMvc.perform(post("/api/v1/players/{playerId}/wallet/credit", playerId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private long creditAndGetTxnId(long playerId, String key, String amount) throws Exception {
        MvcResult r = credit(playerId, key, walletBody(amount, "USD", "ADMIN_ADJUSTMENT", null))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("transactionId").asLong();
    }

    private long debitAndGetTxnId(long playerId, String key, String amount) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/players/{playerId}/wallet/debit", playerId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(walletBody(amount, "USD", "PURCHASE", "order-" + key)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("transactionId").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions transfer(long fromPlayerId,
                                                                        String key,
                                                                        String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/players/{playerId}/wallet/transfer", fromPlayerId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refundRequest(
            long playerId, long transactionId, String key) {
        return post("/api/v1/players/{playerId}/wallet/transactions/{transactionId}/refund",
                playerId, transactionId)
                .header("Idempotency-Key", key);
    }

    private void assertBalance(long playerId, String expectedMajor) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/players/{playerId}/wallet", playerId))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(node.get("balance").decimalValue())
                .isEqualByComparingTo(expectedMajor);
    }

    private String walletBody(String amount, String currencyCode, String reason, String referenceId)
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

    private String transferBody(long toPlayerId, String amount, String currencyCode, String referenceId)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("toPlayerId", toPlayerId);
        request.put("amount", amount);
        request.put("currencyCode", currencyCode);
        if (referenceId != null) {
            request.put("referenceId", referenceId);
        }
        return objectMapper.writeValueAsString(request);
    }
}

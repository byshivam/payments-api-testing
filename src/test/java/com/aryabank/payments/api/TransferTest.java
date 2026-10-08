package com.aryabank.payments.api;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.MoneyAssert.assertMoney;
import static com.aryabank.payments.support.PaymentsApi.newKey;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static com.aryabank.payments.support.PaymentsApi.request;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@DisplayName("Transfers — happy path")
class TransferTest {

    @Test
    @DisplayName("Transfer returns 201 and moves exactly the amount")
    void movesMoney() {
        String payer = openAccount("Asha Verma", "1000.00");
        String payee = openAccount("Ravi Iyer", "200.00");

        request()
                .header("Idempotency-Key", newKey())
                .body(Map.of("from_account", payer, "to_account", payee, "amount", "250.50", "remarks", "Rent October"))
                .post("/transfers")
                .then()
                .statusCode(201)
                .body("transfer_id", matchesPattern("^TXN[0-9A-F]{12}$"))
                .body("from_account", equalTo(payer))
                .body("to_account", equalTo(payee))
                .body("amount", equalTo("250.50"))
                .body("currency", equalTo("INR"))
                .body("status", equalTo("COMPLETED"))
                .body("remarks", equalTo("Rent October"))
                .body("refund_id", nullValue());

        assertBalance(payer, "749.50");
        assertBalance(payee, "450.50");
    }

    @Test
    @DisplayName("GET /transfers/{id} returns the stored transfer")
    void readsTransfer() {
        String payer = openAccount("500.00");
        String payee = openAccount("0.00");
        String id = PaymentsApi.transfer(payer, payee, "75.00").then().statusCode(201).extract().path("transfer_id");

        request().get("/transfers/{id}", id)
                .then()
                .statusCode(200)
                .body("transfer_id", equalTo(id))
                .body("amount", equalTo("75.00"))
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    @DisplayName("Sending the full balance is allowed and leaves 0.00")
    void fullBalanceBoundary() {
        String payer = openAccount("320.45");
        String payee = openAccount("0.00");
        PaymentsApi.transfer(payer, payee, "320.45").then().statusCode(201);
        assertBalance(payer, "0.00");
        assertBalance(payee, "320.45");
    }

    @Test
    @DisplayName("Transfer appears in both accounts' transaction history")
    void appearsInHistory() {
        String payer = openAccount("100.00");
        String payee = openAccount("0.00");
        String id = PaymentsApi.transfer(payer, payee, "40.00").then().statusCode(201).extract().path("transfer_id");

        JsonPath payerTxns = request().get("/accounts/{id}/transactions", payer).then().statusCode(200).extract().jsonPath();
        assertEquals(id, payerTxns.getString("transactions[0].txn_id"));
        assertEquals("DEBIT", payerTxns.getString("transactions[0].direction"));
        assertEquals("40.00", payerTxns.getString("transactions[0].amount"));

        JsonPath payeeTxns = request().get("/accounts/{id}/transactions", payee).then().statusCode(200).extract().jsonPath();
        assertEquals(id, payeeTxns.getString("transactions[0].txn_id"));
        assertEquals("CREDIT", payeeTxns.getString("transactions[0].direction"));
    }

    @Test
    @Tag("db")
    @DisplayName("Transfer writes one DEBIT and one CREDIT of the same amount")
    void writesBalancedLedger() {
        String payer = openAccount("900.00");
        String payee = openAccount("0.00");
        String id = PaymentsApi.transfer(payer, payee, "123.45").then().statusCode(201).extract().path("transfer_id");

        List<LedgerDb.Entry> entries = LedgerDb.entriesFor(id);
        assertEquals(2, entries.size(), "a transfer writes exactly two ledger entries");
        assertEquals(new LedgerDb.Entry(id, payer, "DEBIT", entries.get(0).amount()), entries.get(0));
        assertEquals(new LedgerDb.Entry(id, payee, "CREDIT", entries.get(1).amount()), entries.get(1));
        assertMoney("123.45", entries.get(0).amount(), "debit amount");
        assertMoney("123.45", entries.get(1).amount(), "credit amount");
        assertMoney("776.55", LedgerDb.ledgerBalance(payer), "payer balance rebuilt from ledger");
        assertMoney("776.55", LedgerDb.storedBalance(payer), "payer stored balance");
    }
}

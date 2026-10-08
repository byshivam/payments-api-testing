package com.aryabank.payments.api;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.MoneyAssert.assertMoney;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static com.aryabank.payments.support.PaymentsApi.request;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@DisplayName("Refunds")
class RefundTest {

    private static String pay(String from, String to, String amount) {
        return PaymentsApi.transfer(from, to, amount).then().statusCode(201).extract().path("transfer_id");
    }

    @Test
    @DisplayName("Refund returns the money and marks the transfer REFUNDED")
    void refundsTransfer() {
        String payer = openAccount("1000.00");
        String payee = openAccount("100.00");
        String transferId = pay(payer, payee, "300.00");

        String refundId = PaymentsApi.refund(transferId)
                .then()
                .statusCode(201)
                .body("refund_id", matchesPattern("^RFD[0-9A-F]{12}$"))
                .body("transfer_id", equalTo(transferId))
                .body("amount", equalTo("300.00"))
                .body("status", equalTo("COMPLETED"))
                .extract().path("refund_id");

        assertBalance(payer, "1000.00");
        assertBalance(payee, "100.00");
        request().get("/transfers/{id}", transferId)
                .then()
                .statusCode(200)
                .body("status", equalTo("REFUNDED"))
                .body("refund_id", equalTo(refundId));
    }

    @Test
    @DisplayName("A transfer cannot be refunded twice")
    void noDoubleRefund() {
        String payer = openAccount("1000.00");
        String payee = openAccount("500.00");
        String transferId = pay(payer, payee, "200.00");

        PaymentsApi.refund(transferId).then().statusCode(201);
        PaymentsApi.refund(transferId)
                .then()
                .statusCode(409)
                .body("error.code", equalTo("ALREADY_REFUNDED"));

        assertBalance(payer, "1000.00");
        assertBalance(payee, "500.00");
    }

    @Test
    @DisplayName("Refund of an unknown transfer returns 404")
    void unknownTransfer() {
        PaymentsApi.refund("TXN000000000000")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("Refund fails with INSUFFICIENT_FUNDS if the receiver already spent the money")
    void receiverSpentTheMoney() {
        String payer = openAccount("300.00");
        String payee = openAccount("0.00");
        String other = openAccount("0.00");
        String transferId = pay(payer, payee, "300.00");
        pay(payee, other, "250.00");

        PaymentsApi.refund(transferId)
                .then()
                .statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));

        assertBalance(payer, "0.00");
        assertBalance(payee, "50.00");
    }

    @Test
    @Tag("db")
    @DisplayName("Refund writes a balanced DEBIT (receiver) and CREDIT (payer) in the ledger")
    void refundLedger() {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");
        String transferId = pay(payer, payee, "400.00");
        String refundId = PaymentsApi.refund(transferId).then().statusCode(201).extract().path("refund_id");

        List<LedgerDb.Entry> entries = LedgerDb.entriesFor(refundId);
        assertEquals(2, entries.size(), "refund should write two ledger entries, got " + entries);
        assertEquals("DEBIT", entries.get(0).direction());
        assertEquals(payee, entries.get(0).accountId());
        assertEquals("CREDIT", entries.get(1).direction());
        assertEquals(payer, entries.get(1).accountId());
        assertMoney("400.00", entries.get(0).amount(), "refund debit");
        assertMoney("400.00", entries.get(1).amount(), "refund credit");
    }

    @Test
    @Tag("db")
    @DisplayName("Refund does not create money: payer + payee total is unchanged")
    void refundConservesMoney() {
        String payer = openAccount("1000.00");
        String payee = openAccount("250.00");
        String transferId = pay(payer, payee, "600.00");
        PaymentsApi.refund(transferId).then().statusCode(201);

        BigDecimal total = LedgerDb.storedBalance(payer).add(LedgerDb.storedBalance(payee));
        assertMoney("1250.00", total, "payer + payee after refund");
        assertMoney("0.00", LedgerDb.ledgerBalance(payee).subtract(LedgerDb.storedBalance(payee)),
                "payee stored balance vs ledger");
    }
}

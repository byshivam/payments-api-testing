package com.aryabank.payments.db;

import static com.aryabank.payments.support.MoneyAssert.assertMoney;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation over the whole database, run last (see junit-platform.properties),
 * after every other test has moved money around. These are the checks a bank's
 * end-of-day reconciliation would run.
 */
@Tag("db")
@Order(Integer.MAX_VALUE)
@DisplayName("Ledger reconciliation (whole database)")
class LedgerIntegrityTest {

    @BeforeAll
    static void mixedWorkload() {
        String a = openAccount("5000.00");
        String b = openAccount("1000.00");
        String c = openAccount("0.00");
        PaymentsApi.transfer(a, b, "1200.00").then().statusCode(201);
        PaymentsApi.transfer(b, c, "700.25").then().statusCode(201);
        String t = PaymentsApi.transfer(a, c, "99.99").then().statusCode(201).extract().path("transfer_id");
        PaymentsApi.refund(t).then().statusCode(201);
        PaymentsApi.transfer(c, a, "0.01").then().statusCode(201);
    }

    @Test
    @DisplayName("Every transaction's debits equal its credits")
    void everyTransactionBalances() {
        List<String> bad = LedgerDb.unbalancedTransactions();
        assertEquals(List.of(), bad, "unbalanced transactions");
    }

    @Test
    @DisplayName("Every account's stored balance equals credits minus debits in the ledger")
    void balancesMatchLedger() {
        List<String> bad = LedgerDb.accountsOutOfSync();
        assertEquals(List.of(), bad, "accounts whose balance disagrees with the ledger");
    }

    @Test
    @DisplayName("Money is conserved: all balances (incl. the funding account) sum to 0.00")
    void moneyIsConserved() {
        assertMoney("0.00", LedgerDb.totalMoney(), "sum of all balances");
    }

    @Test
    @DisplayName("No customer account is negative")
    void noNegativeBalances() {
        assertEquals(List.of(), LedgerDb.negativeCustomerBalances(), "customer accounts below zero");
    }

    @Test
    @DisplayName("No ledger entry has a zero or negative amount")
    void noNonPositiveEntries() {
        assertEquals(List.of(), LedgerDb.nonPositiveEntries(), "ledger entries with amount <= 0");
    }
}

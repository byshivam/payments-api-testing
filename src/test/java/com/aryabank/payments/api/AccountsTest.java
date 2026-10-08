package com.aryabank.payments.api;

import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static com.aryabank.payments.support.PaymentsApi.request;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.MoneyAssert;
import com.aryabank.payments.support.PaymentsApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("api")
@DisplayName("Accounts")
class AccountsTest {

    @Test
    @DisplayName("Opening an account returns 201 with id, balance, INR and ACTIVE")
    void opensAccount() {
        request()
                .body(Map.of("holder_name", "Asha Verma", "opening_balance", "1500.00"))
                .post("/accounts")
                .then()
                .statusCode(201)
                .body("account_id", matchesPattern("^AB\\d{6}$"))
                .body("holder_name", equalTo("Asha Verma"))
                .body("balance", equalTo("1500.00"))
                .body("currency", equalTo("INR"))
                .body("status", equalTo("ACTIVE"))
                .body("created_at", notNullValue());
    }

    @Test
    @DisplayName("Balance is always a string with exactly two decimals")
    void balanceIsTwoDecimalString() {
        String id = openAccount("1000.5");
        PaymentsApi.getAccount(id)
                .then()
                .statusCode(200)
                .body("balance", instanceOf(String.class))
                .body("balance", equalTo("1000.50"));
    }

    @Test
    @DisplayName("GET /accounts/{id} returns what was opened")
    void readsAccount() {
        String id = openAccount("Ravi Iyer", "250.75");
        PaymentsApi.getAccount(id)
                .then()
                .statusCode(200)
                .body("account_id", equalTo(id))
                .body("holder_name", equalTo("Ravi Iyer"))
                .body("balance", equalTo("250.75"));
    }

    @Test
    @DisplayName("Unknown account returns 404 ACCOUNT_NOT_FOUND")
    void unknownAccount() {
        PaymentsApi.getAccount("AB999999")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @ParameterizedTest(name = "opening_balance \"{0}\" is rejected")
    @ValueSource(strings = {"abc", "10.999", "-5.00", "", "1,000.00"})
    @DisplayName("Invalid opening balances are rejected with 422 INVALID_AMOUNT")
    void rejectsInvalidOpeningBalance(String openingBalance) {
        request()
                .body(Map.of("holder_name", "Bad Input", "opening_balance", openingBalance))
                .post("/accounts")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("INVALID_AMOUNT"));
    }

    @Test
    @DisplayName("Blank holder name is rejected with 422")
    void rejectsBlankName() {
        request()
                .body(Map.of("holder_name", "   ", "opening_balance", "10.00"))
                .post("/accounts")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Tag("db")
    @DisplayName("Opening balance is funded through the ledger (system debit, customer credit)")
    void openingBalanceIsInLedger() {
        String id = openAccount("800.00");
        List<LedgerDb.Entry> entries = LedgerDb.entriesFor("OPEN-" + id);
        assertEquals(2, entries.size(), "opening should write exactly two ledger entries");
        assertEquals("DEBIT", entries.get(0).direction());
        assertEquals("AB000000", entries.get(0).accountId());
        assertEquals("CREDIT", entries.get(1).direction());
        assertEquals(id, entries.get(1).accountId());
        MoneyAssert.assertMoney("800.00", entries.get(1).amount(), "credited amount");
        MoneyAssert.assertMoney("800.00", LedgerDb.storedBalance(id), "stored balance");
    }
}

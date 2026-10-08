package com.aryabank.payments.api;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.PaymentsApi.newKey;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static com.aryabank.payments.support.PaymentsApi.request;
import static org.hamcrest.Matchers.equalTo;

import com.aryabank.payments.support.PaymentsApi;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("api")
@DisplayName("Transfers — validation and negative cases")
class TransferValidationTest {

    @ParameterizedTest(name = "amount \"{0}\" → 422 INVALID_AMOUNT, no money moves")
    @ValueSource(strings = {"0", "0.00", "-100.00", "-0.01", "10.999", "ten", "1e3", " 100", ""})
    @DisplayName("Invalid amounts are rejected and no money moves")
    void rejectsInvalidAmounts(String amount) {
        String payer = openAccount("1000.00");
        String payee = openAccount("1000.00");

        PaymentsApi.transfer(payer, payee, amount)
                .then()
                .statusCode(422)
                .body("error.code", equalTo("INVALID_AMOUNT"));

        assertBalance(payer, "1000.00");
        assertBalance(payee, "1000.00");
    }

    @Test
    @DisplayName("Amount sent as a JSON number is rejected (money must be a string)")
    void rejectsNumericAmount() {
        String payer = openAccount("100.00");
        String payee = openAccount("0.00");
        Map<String, Object> body = new HashMap<>();
        body.put("from_account", payer);
        body.put("to_account", payee);
        body.put("amount", 10.5);

        request().header("Idempotency-Key", newKey()).body(body).post("/transfers")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "balance {0}, send {1} → 422 INSUFFICIENT_FUNDS")
    @CsvSource({
            "500.50, 500.51",  // one paisa short
            "500.50, 500.90",  // same whole rupees, 40 paise short
            "0.00,   0.01",
            "99.99,  100.00",
    })
    @DisplayName("Insufficient funds is rejected at the paisa boundary and no money moves")
    void rejectsInsufficientFunds(String balance, String amount) {
        String payer = openAccount(balance);
        String payee = openAccount("0.00");

        PaymentsApi.transfer(payer, payee, amount)
                .then()
                .statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));

        assertBalance(payer, balance);
        assertBalance(payee, "0.00");
    }

    @Test
    @DisplayName("Per-transfer limit: 100000.00 is allowed, 100000.01 is rejected")
    void transferLimitBoundary() {
        String payer = openAccount("250000.00");
        String payee = openAccount("0.00");

        PaymentsApi.transfer(payer, payee, "100000.01")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("LIMIT_EXCEEDED"));
        PaymentsApi.transfer(payer, payee, "100000.00").then().statusCode(201);

        assertBalance(payer, "150000.00");
    }

    @Test
    @DisplayName("Transfer to the same account is rejected with SAME_ACCOUNT")
    void rejectsSameAccount() {
        String account = openAccount("100.00");
        PaymentsApi.transfer(account, account, "10.00")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("SAME_ACCOUNT"));
        assertBalance(account, "100.00");
    }

    @Test
    @DisplayName("Unknown payer returns 404 and the payee is untouched")
    void unknownPayer() {
        String payee = openAccount("50.00");
        PaymentsApi.transfer("AB999998", payee, "10.00")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("ACCOUNT_NOT_FOUND"));
        assertBalance(payee, "50.00");
    }

    @Test
    @DisplayName("Unknown payee returns 404 and the payer is not debited")
    void unknownPayee() {
        String payer = openAccount("50.00");
        PaymentsApi.transfer(payer, "AB999997", "10.00")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("ACCOUNT_NOT_FOUND"));
        assertBalance(payer, "50.00");
    }

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400")
    void requiresIdempotencyKey() {
        String payer = openAccount("50.00");
        String payee = openAccount("0.00");
        request()
                .body(Map.of("from_account", payer, "to_account", payee, "amount", "10.00"))
                .post("/transfers")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("MISSING_IDEMPOTENCY_KEY"));
        assertBalance(payer, "50.00");
    }

    @Test
    @DisplayName("Missing required field returns 422 VALIDATION_ERROR")
    void missingField() {
        String payer = openAccount("50.00");
        request()
                .header("Idempotency-Key", newKey())
                .body(Map.of("from_account", payer, "amount", "10.00"))
                .post("/transfers")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }
}

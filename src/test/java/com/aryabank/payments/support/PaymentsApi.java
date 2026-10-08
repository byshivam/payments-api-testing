package com.aryabank.payments.support;

import static io.restassured.RestAssured.given;

import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin client over the payments API. Tests use it for setup and for the
 * calls they are not asserting on; the request under test is usually written
 * out in full in the test so the reader sees exactly what is sent.
 */
public final class PaymentsApi {

    private PaymentsApi() {
    }

    public static RequestSpecification request() {
        return given()
                .baseUri(Config.BASE_URL)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .filter(new AllureRestAssured());
    }

    /** Opens a new account and returns its id. */
    public static String openAccount(String holderName, String openingBalance) {
        return request()
                .body(Map.of("holder_name", holderName, "opening_balance", openingBalance))
                .post("/accounts")
                .then()
                .statusCode(201)
                .extract()
                .path("account_id");
    }

    public static String openAccount(String openingBalance) {
        return openAccount("Test Customer", openingBalance);
    }

    public static Response getAccount(String accountId) {
        return request().get("/accounts/{id}", accountId);
    }

    /** Balance as BigDecimal; tolerant of the number-vs-string drift so setup does not hide the real failure. */
    public static BigDecimal balance(String accountId) {
        Response response = getAccount(accountId);
        response.then().statusCode(200);
        return new BigDecimal(response.jsonPath().getString("balance"));
    }

    public static Response transfer(String from, String to, String amount, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("from_account", from);
        body.put("to_account", to);
        body.put("amount", amount);
        return request()
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .post("/transfers");
    }

    public static Response transfer(String from, String to, String amount) {
        return transfer(from, to, amount, newKey());
    }

    public static Response refund(String transferId) {
        return request().post("/transfers/{id}/refund", transferId);
    }

    public static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    /** Fixed-id account via the test hook (only used by Pact provider states). */
    public static void seedAccount(String accountId, String holderName, String balance) {
        request()
                .body(Map.of("account_id", accountId, "holder_name", holderName, "balance", balance))
                .post("/_test/accounts")
                .then()
                .statusCode(201);
    }

    public static void forgetIdempotencyKey(String key) {
        request().delete("/_test/idempotency-keys/{key}", key).then().statusCode(204);
    }
}

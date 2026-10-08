package com.aryabank.payments.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Consumer side of the contract: what the Arya Bank mobile app expects from
 * the payments API. Running these tests writes target/pacts/*.json;
 * {@link PaymentsProviderPactIT} then replays that file against the real API.
 */
@Tag("contract")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "arya-payments-api", pactVersion = PactSpecVersion.V3)
@DisplayName("Contract — mobile app (consumer)")
class MobileAppConsumerPactTest {

    static final String CONSUMER = "arya-mobile-app";
    static final String MONEY = "^\\d+\\.\\d{2}$";

    @Pact(consumer = CONSUMER)
    RequestResponsePact accountExists(PactDslWithProvider builder) {
        return builder
                .given("account AB900001 exists with balance 1000.00")
                .uponReceiving("a request for an existing account")
                .path("/accounts/AB900001")
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                        .stringValue("account_id", "AB900001")
                        .stringType("holder_name", "Asha Verma")
                        .stringMatcher("balance", MONEY, "1000.00")
                        .stringValue("currency", "INR")
                        .stringValue("status", "ACTIVE"))
                .toPact();
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact accountMissing(PactDslWithProvider builder) {
        return builder
                .given("account AB999999 does not exist")
                .uponReceiving("a request for an unknown account")
                .path("/accounts/AB999999")
                .method("GET")
                .willRespondWith()
                .status(404)
                .body(new PactDslJsonBody()
                        .object("error")
                        .stringValue("code", "ACCOUNT_NOT_FOUND")
                        .stringType("message", "Account AB999999 not found")
                        .closeObject())
                .toPact();
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact transferSucceeds(PactDslWithProvider builder) {
        return builder
                .given("accounts AB900001 (1000.00) and AB900002 exist")
                .uponReceiving("a transfer the payer can afford")
                .path("/transfers")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json", "Idempotency-Key", "pact-transfer-0001"))
                .body(new PactDslJsonBody()
                        .stringValue("from_account", "AB900001")
                        .stringValue("to_account", "AB900002")
                        .stringValue("amount", "250.00"))
                .willRespondWith()
                .status(201)
                .body(new PactDslJsonBody()
                        .stringMatcher("transfer_id", "^TXN[0-9A-F]{12}$", "TXN0A1B2C3D4E5F")
                        .stringValue("from_account", "AB900001")
                        .stringValue("to_account", "AB900002")
                        .stringValue("amount", "250.00")
                        .stringValue("currency", "INR")
                        .stringValue("status", "COMPLETED"))
                .toPact();
    }

    @Pact(consumer = CONSUMER)
    RequestResponsePact transferInsufficientFunds(PactDslWithProvider builder) {
        return builder
                .given("account AB900003 has balance 10.00 and AB900002 exists")
                .uponReceiving("a transfer larger than the balance")
                .path("/transfers")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json", "Idempotency-Key", "pact-transfer-0002"))
                .body(new PactDslJsonBody()
                        .stringValue("from_account", "AB900003")
                        .stringValue("to_account", "AB900002")
                        .stringValue("amount", "50.00"))
                .willRespondWith()
                .status(422)
                .body(new PactDslJsonBody()
                        .object("error")
                        .stringValue("code", "INSUFFICIENT_FUNDS")
                        .stringType("message", "Insufficient funds in AB900003")
                        .closeObject())
                .toPact();
    }

    // ---- the mobile app's calls, run against the Pact mock server

    @Test
    @PactTestFor(pactMethod = "accountExists")
    @DisplayName("App shows the balance of an existing account")
    void showsBalance(MockServer server) {
        given().baseUri(server.getUrl())
                .get("/accounts/AB900001")
                .then()
                .statusCode(200)
                .body("balance", matchesPattern(MONEY))
                .body("currency", equalTo("INR"));
    }

    @Test
    @PactTestFor(pactMethod = "accountMissing")
    @DisplayName("App shows 'account not found'")
    void showsNotFound(MockServer server) {
        given().baseUri(server.getUrl())
                .get("/accounts/AB999999")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @PactTestFor(pactMethod = "transferSucceeds")
    @DisplayName("App sends a transfer and reads the confirmation")
    void sendsTransfer(MockServer server) {
        given().baseUri(server.getUrl())
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "pact-transfer-0001")
                .body(Map.of("from_account", "AB900001", "to_account", "AB900002", "amount", "250.00"))
                .post("/transfers")
                .then()
                .statusCode(201)
                .body("status", equalTo("COMPLETED"))
                .body("amount", equalTo("250.00"));
    }

    @Test
    @PactTestFor(pactMethod = "transferInsufficientFunds")
    @DisplayName("App shows 'insufficient funds'")
    void showsInsufficientFunds(MockServer server) {
        given().baseUri(server.getUrl())
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "pact-transfer-0002")
                .body(Map.of("from_account", "AB900003", "to_account", "AB900002", "amount", "50.00"))
                .post("/transfers")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("INSUFFICIENT_FUNDS"));
    }
}

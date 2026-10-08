package com.aryabank.payments.api;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.PaymentsApi.newKey;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A payment app retries when the network drops. The retry carries the same
 * Idempotency-Key, and the customer must be debited exactly once.
 */
@Tag("api")
@DisplayName("Transfers — idempotency")
class IdempotencyTest {

    @Test
    @DisplayName("Retry with the same key returns the original transfer and debits once")
    void retryDebitsOnce() {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");
        String key = newKey();

        String firstId = PaymentsApi.transfer(payer, payee, "100.00", key)
                .then().statusCode(201).extract().path("transfer_id");

        PaymentsApi.transfer(payer, payee, "100.00", key)
                .then()
                .statusCode(200)
                .header("Idempotent-Replayed", "true")
                .body("transfer_id", equalTo(firstId));

        assertBalance(payer, "900.00");
        assertBalance(payee, "100.00");
    }

    @Test
    @DisplayName("Same key with a different amount is rejected with 409 and nothing moves")
    void sameKeyDifferentBody() {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");
        String key = newKey();

        PaymentsApi.transfer(payer, payee, "100.00", key).then().statusCode(201);
        PaymentsApi.transfer(payer, payee, "999.00", key)
                .then()
                .statusCode(409)
                .body("error.code", equalTo("IDEMPOTENCY_CONFLICT"));

        assertBalance(payer, "900.00");
    }

    @Test
    @DisplayName("Different keys are different payments")
    void differentKeys() {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");
        PaymentsApi.transfer(payer, payee, "100.00", newKey()).then().statusCode(201);
        PaymentsApi.transfer(payer, payee, "100.00", newKey()).then().statusCode(201);
        assertBalance(payer, "800.00");
    }

    @Test
    @Tag("concurrency")
    @DisplayName("10 parallel retries with one key create exactly one transfer")
    void parallelRetries() throws Exception {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");
        String key = newKey();
        int retries = 10;

        ExecutorService pool = Executors.newFixedThreadPool(retries);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < retries; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return PaymentsApi.transfer(payer, payee, "100.00", key);
            }));
        }
        start.countDown();

        Set<String> transferIds = new HashSet<>();
        for (Future<Response> f : futures) {
            Response r = f.get();
            transferIds.add(r.jsonPath().getString("transfer_id"));
        }
        pool.shutdown();

        assertEquals(1, transferIds.size(), "all retries should return the same transfer id, got " + transferIds);
        assertBalance(payer, "900.00");
        assertBalance(payee, "100.00");
    }

    @Test
    @Tag("db")
    @DisplayName("A retried request leaves exactly one transfer row in the database")
    void oneRowInDb() {
        String payer = openAccount("500.00");
        String payee = openAccount("0.00");
        String key = newKey();
        for (int i = 0; i < 3; i++) {
            PaymentsApi.transfer(payer, payee, "50.00", key);
        }
        assertEquals(1, LedgerDb.transferCount(payer, payee), "transfer rows for the retried payment");
    }
}

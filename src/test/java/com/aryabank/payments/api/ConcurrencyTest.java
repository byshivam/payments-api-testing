package com.aryabank.payments.api;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.MoneyAssert.assertMoney;
import static com.aryabank.payments.support.PaymentsApi.openAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Parallel payments from one account: no lost updates, no overdraft. */
@Tag("api")
@Tag("concurrency")
@DisplayName("Transfers — concurrency")
class ConcurrencyTest {

    /** Fires the same transfer {@code count} times at once and returns the status codes. */
    private static List<Integer> fireInParallel(String from, String to, String amount, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return PaymentsApi.transfer(from, to, amount).statusCode();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();
        return codes;
    }

    @Test
    @DisplayName("25 parallel transfers of 10.00: every rupee is accounted for")
    void noLostUpdates() throws Exception {
        String payer = openAccount("1000.00");
        String payee = openAccount("0.00");

        List<Integer> codes = fireInParallel(payer, payee, "10.00", 25);

        assertEquals(25, codes.stream().filter(c -> c == 201).count(), "all transfers should succeed: " + codes);
        assertBalance(payer, "750.00");
        assertBalance(payee, "250.00");
    }

    @Test
    @DisplayName("20 parallel transfers of 10.00 from 100.00: exactly 10 succeed, never negative")
    void noOverdraftUnderLoad() throws Exception {
        String payer = openAccount("100.00");
        String payee = openAccount("0.00");

        List<Integer> codes = fireInParallel(payer, payee, "10.00", 20);

        assertEquals(10, codes.stream().filter(c -> c == 201).count(), "successful transfers: " + codes);
        assertEquals(10, codes.stream().filter(c -> c == 422).count(), "rejected for funds: " + codes);
        assertBalance(payer, "0.00");
        assertBalance(payee, "100.00");
    }

    @Test
    @Tag("db")
    @DisplayName("After parallel transfers the stored balances still match the ledger")
    void balancesMatchLedger() throws Exception {
        String payer = openAccount("500.00");
        String payee = openAccount("0.00");

        fireInParallel(payer, payee, "5.00", 30);

        assertMoney("0.00", LedgerDb.storedBalance(payer).subtract(LedgerDb.ledgerBalance(payer)), "payer drift");
        assertMoney("0.00", LedgerDb.storedBalance(payee).subtract(LedgerDb.ledgerBalance(payee)), "payee drift");
    }
}

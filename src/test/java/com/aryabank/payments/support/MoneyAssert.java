package com.aryabank.payments.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

/** Compares money by value, so 100.0 and 100.00 are equal; the string format is tested separately. */
public final class MoneyAssert {

    private MoneyAssert() {
    }

    public static void assertMoney(String expected, BigDecimal actual, String message) {
        BigDecimal want = new BigDecimal(expected);
        assertEquals(0, want.compareTo(actual), message + " — expected " + want + " but was " + actual);
    }

    public static void assertBalance(String accountId, String expected) {
        assertMoney(expected, PaymentsApi.balance(accountId), "balance of " + accountId);
    }
}

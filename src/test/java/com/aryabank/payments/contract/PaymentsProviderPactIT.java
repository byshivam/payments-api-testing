package com.aryabank.payments.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.aryabank.payments.support.Config;
import com.aryabank.payments.support.PaymentsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Provider side: replays every interaction in target/pacts against the running
 * API. Provider states set up data through the API's test hook, so the ledger
 * stays consistent.
 */
@Tag("contract")
@Provider("arya-payments-api")
@PactFolder("target/pacts")
@DisplayName("Contract — payments API (provider verification)")
class PaymentsProviderPactIT {

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget(Config.host(), Config.port()));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("account AB900001 exists with balance 1000.00")
    void accountExists() {
        PaymentsApi.seedAccount("AB900001", "Asha Verma", "1000.00");
    }

    @State("account AB999999 does not exist")
    void accountMissing() {
        // nothing to set up: AB999999 is never created
    }

    @State("accounts AB900001 (1000.00) and AB900002 exist")
    void twoAccounts() {
        PaymentsApi.seedAccount("AB900001", "Asha Verma", "1000.00");
        PaymentsApi.seedAccount("AB900002", "Ravi Iyer", "0.00");
        PaymentsApi.forgetIdempotencyKey("pact-transfer-0001");
    }

    @State("account AB900003 has balance 10.00 and AB900002 exists")
    void lowBalance() {
        PaymentsApi.seedAccount("AB900003", "Meera Nair", "10.00");
        PaymentsApi.seedAccount("AB900002", "Ravi Iyer", "0.00");
    }
}

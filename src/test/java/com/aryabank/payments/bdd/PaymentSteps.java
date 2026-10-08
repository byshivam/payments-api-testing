package com.aryabank.payments.bdd;

import static com.aryabank.payments.support.MoneyAssert.assertBalance;
import static com.aryabank.payments.support.MoneyAssert.assertMoney;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aryabank.payments.support.LedgerDb;
import com.aryabank.payments.support.PaymentsApi;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;

/**
 * Step definitions. They reuse the same API client and JDBC ledger queries as the
 * JUnit tests, so the BDD layer adds readable scenarios without a second framework.
 */
public class PaymentSteps {

    private final ScenarioContext ctx;

    public PaymentSteps(ScenarioContext ctx) {
        this.ctx = ctx;
    }

    /** Money as written in the feature file, e.g. 1000.50 or -100.00; kept as text, like the API. */
    @ParameterType("-?\\d+(?:\\.\\d+)?")
    public String money(String value) {
        return value;
    }

    // ---------------------------------------------------------------- Given

    @Given("{word} has an account with balance {money}")
    public void hasAccount(String name, String balance) {
        ctx.addAccount(name, PaymentsApi.openAccount(name + " Test", balance));
    }

    @Given("these customers:")
    public void theseCustomers(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            hasAccount(row.get("name"), row.get("balance"));
        }
    }

    // ---------------------------------------------------------------- When

    @When("{word} sends {money} to {word}")
    public void sends(String from, String amount, String to) {
        record(PaymentsApi.transfer(ctx.account(from), ctx.account(to), amount));
    }

    @When("{word} sends {money} to {word} with idempotency key {string}")
    public void sendsWithKey(String from, String amount, String to, String key) {
        record(PaymentsApi.transfer(ctx.account(from), ctx.account(to), amount, ctx.idempotencyKey(key)));
    }

    @And("the app retries the same payment with idempotency key {string}")
    public void retries(String key) {
        Response first = ctx.transferResponses.get(0);
        record(PaymentsApi.transfer(first.path("from_account"), first.path("to_account"), first.path("amount"),
                ctx.idempotencyKey(key)));
    }

    @When("these payments are made:")
    public void paymentsAreMade(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            sends(row.get("from"), row.get("amount"), row.get("to"));
            assertEquals(201, ctx.lastTransfer().statusCode(),
                    "payment " + row + " failed: " + ctx.lastTransfer().asString());
        }
    }

    @When("the last transfer is refunded")
    public void refundLast() {
        ctx.lastRefund = PaymentsApi.refund(ctx.lastTransferId);
    }

    @When("the last transfer is refunded again")
    public void refundAgain() {
        refundLast();
    }

    // ---------------------------------------------------------------- Then

    @Then("the transfer is completed")
    public void transferCompleted() {
        Response r = ctx.lastTransfer();
        assertEquals(201, r.statusCode(), r.asString());
        assertEquals("COMPLETED", r.path("status"));
    }

    @Then("the transfer is rejected with {string}")
    public void transferRejected(String code) {
        Response r = ctx.lastTransfer();
        assertTrue(r.statusCode() >= 400 && r.statusCode() < 500, "expected a 4xx, got " + r.statusCode() + " " + r.asString());
        assertEquals(code, r.path("error.code"));
    }

    @Then("{word}'s balance is {money}")
    public void balanceIs(String name, String expected) {
        assertBalance(ctx.account(name), expected);
    }

    @Then("the balances are:")
    public void balancesAre(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            balanceIs(row.get("name"), row.get("balance"));
        }
    }

    @Then("both responses show the same transfer")
    public void sameTransfer() {
        List<Response> rs = ctx.transferResponses;
        assertEquals(2, rs.size());
        assertEquals((String) rs.get(0).path("transfer_id"), (String) rs.get(1).path("transfer_id"),
                "a retry with the same key must return the original transfer");
    }

    @Then("there is/are {int} transfer(s) from {word} to {word}")
    public void transferCount(int expected, String from, String to) {
        assertEquals(expected, LedgerDb.transferCount(ctx.account(from), ctx.account(to)));
    }

    @Then("the ledger for the last transfer is balanced")
    public void lastTransferBalanced() {
        assertBalancedPair(ctx.lastTransferId);
    }

    @Then("the refund is completed")
    public void refundCompleted() {
        assertEquals(201, ctx.lastRefund.statusCode(), ctx.lastRefund.asString());
        assertEquals("COMPLETED", ctx.lastRefund.path("status"));
    }

    @Then("the refund is rejected with {string}")
    public void refundRejected(String code) {
        assertEquals(409, ctx.lastRefund.statusCode(), ctx.lastRefund.asString());
        assertEquals(code, ctx.lastRefund.path("error.code"));
    }

    @Then("the original transfer is marked {word}")
    public void originalMarked(String status) {
        PaymentsApi.request().get("/transfers/{id}", ctx.lastTransferId).then().statusCode(200);
        assertEquals(status, PaymentsApi.request().get("/transfers/{id}", ctx.lastTransferId).path("status"));
    }

    @Then("the ledger for the refund is balanced")
    public void refundBalanced() {
        assertBalancedPair(ctx.lastRefund.path("refund_id"));
    }

    @Then("every transaction in the ledger is balanced")
    public void everyTransactionBalanced() {
        assertEquals(List.of(), LedgerDb.unbalancedTransactions(), "unbalanced transactions");
        assertEquals(List.of(), LedgerDb.accountsOutOfSync(), "accounts whose balance disagrees with the ledger");
    }

    @Then("no money was created or lost")
    public void moneyConserved() {
        assertMoney("0.00", LedgerDb.totalMoney(), "sum of all balances including the funding account");
    }

    // ---------------------------------------------------------------- helpers

    private void record(Response r) {
        ctx.transferResponses.add(r);
        if (r.statusCode() == 201) {
            ctx.lastTransferId = r.path("transfer_id");
        }
    }

    private static void assertBalancedPair(String txnId) {
        List<LedgerDb.Entry> entries = LedgerDb.entriesFor(txnId);
        assertEquals(2, entries.size(), "ledger entries for " + txnId + ": " + entries);
        assertEquals("DEBIT", entries.get(0).direction());
        assertEquals("CREDIT", entries.get(1).direction());
        assertEquals(0, entries.get(0).amount().compareTo(entries.get(1).amount()), "debit must equal credit");
    }
}

package com.aryabank.payments.bdd;

import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State shared by the step classes within one scenario (PicoContainer creates a new
 * instance per scenario). Feature files talk about people ("Asha", "Ravi"); this maps
 * those names to the account ids the API actually created.
 */
public class ScenarioContext {

    private final Map<String, String> accounts = new HashMap<>();
    private final Map<String, String> idempotencyKeys = new HashMap<>();
    private final String scenarioId = UUID.randomUUID().toString().substring(0, 8);

    final List<Response> transferResponses = new ArrayList<>();
    Response lastRefund;
    String lastTransferId;

    void addAccount(String name, String accountId) {
        accounts.put(name, accountId);
    }

    String account(String name) {
        String id = accounts.get(name);
        if (id == null) {
            throw new IllegalStateException("No account for '" + name + "' in this scenario. Known: " + accounts.keySet());
        }
        return id;
    }

    /** A key named in the feature file, made unique per scenario so scenarios never collide. */
    String idempotencyKey(String alias) {
        return idempotencyKeys.computeIfAbsent(alias, a -> "bdd-" + scenarioId + "-" + a);
    }

    Response lastTransfer() {
        if (transferResponses.isEmpty()) {
            throw new IllegalStateException("No transfer was made in this scenario");
        }
        return transferResponses.get(transferResponses.size() - 1);
    }
}

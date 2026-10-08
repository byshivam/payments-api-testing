@bdd
Feature: Safe retries
  A payment app retries when the network drops. The retry carries the same
  Idempotency-Key, so the customer must be charged only once.

  Background:
    Given Asha has an account with balance 1000.00
    And Ravi has an account with balance 0.00

  Scenario: A retried payment is charged once
    When Asha sends 100.00 to Ravi with idempotency key "rent-october"
    And the app retries the same payment with idempotency key "rent-october"
    Then both responses show the same transfer
    And Asha's balance is 900.00
    And Ravi's balance is 100.00
    And there is 1 transfer from Asha to Ravi

  Scenario: A key can't be reused for a different payment
    When Asha sends 100.00 to Ravi with idempotency key "rent-october"
    And Asha sends 999.00 to Ravi with idempotency key "rent-october"
    Then the transfer is rejected with "IDEMPOTENCY_CONFLICT"
    And Asha's balance is 900.00

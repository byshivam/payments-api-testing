@bdd
Feature: Refunds
  A completed transfer can be refunded once, in full.

  Background:
    Given Asha has an account with balance 1000.00
    And Ravi has an account with balance 500.00
    And Asha sends 200.00 to Ravi

  Scenario: A refund returns the money to the payer
    When the last transfer is refunded
    Then the refund is completed
    And Asha's balance is 1000.00
    And Ravi's balance is 500.00
    And the original transfer is marked REFUNDED
    And the ledger for the refund is balanced

  Scenario: A transfer can't be refunded twice
    When the last transfer is refunded
    And the last transfer is refunded again
    Then the refund is rejected with "ALREADY_REFUNDED"
    And Asha's balance is 1000.00
    And Ravi's balance is 500.00

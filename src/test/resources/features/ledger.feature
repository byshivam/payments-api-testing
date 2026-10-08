@bdd
Feature: End-of-day reconciliation
  Every rupee that leaves one account arrives in another. At the end of the
  day the double-entry ledger must balance and no money is created or lost.

  Scenario: A day of payments and a refund leaves the books balanced
    Given these customers:
      | name  | balance |
      | Asha  | 5000.00 |
      | Ravi  | 1000.00 |
      | Meera | 0.00    |
    When these payments are made:
      | from | to    | amount  |
      | Asha | Ravi  | 1200.00 |
      | Ravi | Meera | 700.25  |
      | Asha | Meera | 99.99   |
    And the last transfer is refunded
    Then the balances are:
      | name  | balance |
      | Asha  | 3800.00 |
      | Ravi  | 1499.75 |
      | Meera | 700.25  |
    And every transaction in the ledger is balanced
    And no money was created or lost

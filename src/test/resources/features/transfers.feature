@bdd
Feature: Money transfer
  As an Arya Bank customer
  I want to send money to another account
  So that I can pay people and know exactly what left my account

  Background:
    Given Asha has an account with balance 1000.50
    And Ravi has an account with balance 0.00

  Scenario: Customer sends money with paise
    When Asha sends 250.25 to Ravi
    Then the transfer is completed
    And Asha's balance is 750.25
    And Ravi's balance is 250.25
    And the ledger for the last transfer is balanced

  Scenario: Customer can send their full balance
    When Asha sends 1000.50 to Ravi
    Then the transfer is completed
    And Asha's balance is 0.00
    And Ravi's balance is 1000.50

  Scenario Outline: A transfer the payer can't afford is rejected and nothing moves
    Given Meera has an account with balance <balance>
    When Meera sends <amount> to Ravi
    Then the transfer is rejected with "INSUFFICIENT_FUNDS"
    And Meera's balance is <balance>
    And Ravi's balance is 0.00

    Examples: one paisa short, whole rupees equal, empty account
      | balance | amount |
      | 500.50  | 500.51 |
      | 500.50  | 500.90 |
      | 0.00    | 0.01   |

  Scenario Outline: Invalid amounts are rejected
    When Asha sends <amount> to Ravi
    Then the transfer is rejected with "INVALID_AMOUNT"
    And Asha's balance is 1000.50

    Examples:
      | amount  |
      | 0.00    |
      | -100.00 |
      | 10.999  |

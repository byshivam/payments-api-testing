# Bug hunt

_Last run: 08 Oct 2026 14:28 UTC_

**Clean build:** 77/77 tests passed (no false alarms).  
**Planted bugs caught:** 7/7.  
**Release candidate with every bug on:** 35 of 77 tests fail.  
**Load (k6):** 1221 requests, p95 4 ms, 0.00% errors, money conserved.  

| Bug | What it does | Result | Caught by | Failing tests |
|---|---|---|---|---|
| BUG-01 | Idempotency key ignored | ✅ caught | BDD, API, DB, Concurrency | 6 |
| BUG-02 | Funds check compares whole rupees only | ✅ caught | BDD, API, DB | 7 |
| BUG-03 | Negative amounts accepted | ✅ caught | BDD, API, DB | 6 |
| BUG-04 | Transfer can be refunded twice | ✅ caught | BDD, API | 2 |
| BUG-05 | Refund never debits the receiver | ✅ caught | BDD, API, DB | 9 |
| BUG-06 | Contract drift on account balance | ✅ caught | API, Contract | 4 |
| BUG-07 | Lost update under concurrent transfers | ✅ caught | BDD, DB, Concurrency | 5 |

<details><summary>Which tests caught which bug</summary>

**BUG-01 — Idempotency key ignored.** A retried payment (same Idempotency-Key) debits the customer twice.

- `Concurrency` 10 parallel retries with one key create exactly one transfer
- `BDD` A retried payment is charged once
- `BDD` A key can't be reused for a different payment
- `DB` A retried request leaves exactly one transfer row in the database
- `API` Retry with the same key returns the original transfer and debits once
- `API` Same key with a different amount is rejected with 409 and nothing moves

**BUG-02 — Funds check compares whole rupees only.** Balance 500.50 can send 500.90; the account goes negative.

- `BDD` A transfer the payer can't afford is rejected and nothing moves [0.00, 0.01]
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 0.00, send 0.01 → 422 INSUFFICIENT_FUNDS
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.90 → 422 INSUFFICIENT_FUNDS
- `BDD` A transfer the payer can't afford is rejected and nothing moves [500.50, 500.90]
- `BDD` A transfer the payer can't afford is rejected and nothing moves [500.50, 500.51]
- `DB` No customer account is negative
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.51 → 422 INSUFFICIENT_FUNDS

**BUG-03 — Negative amounts accepted.** A transfer of -100.00 pulls money from the receiver.

- `BDD` Invalid amounts are rejected [-100.00]
- `DB` No customer account is negative
- `API` Invalid opening balances are rejected with 422 INVALID_AMOUNT opening_balance "-5.00" is rejected
- `API` Invalid amounts are rejected and no money moves amount "-100.00" → 422 INVALID_AMOUNT, no money moves
- `API` Invalid amounts are rejected and no money moves amount "-0.01" → 422 INVALID_AMOUNT, no money moves
- `DB` No ledger entry has a zero or negative amount

**BUG-04 — Transfer can be refunded twice.** Sender gets the money back twice.

- `API` A transfer cannot be refunded twice
- `BDD` A transfer can't be refunded twice

**BUG-05 — Refund never debits the receiver.** Refund creates money; the double-entry ledger no longer balances.

- `DB` Refund writes a balanced DEBIT (receiver) and CREDIT (payer) in the ledger
- `DB` Every transaction's debits equal its credits
- `BDD` A day of payments and a refund leaves the books balanced
- `BDD` A refund returns the money to the payer
- `BDD` A transfer can't be refunded twice
- `API` A transfer cannot be refunded twice
- `DB` Money is conserved: all balances (incl. the funding account) sum to 0.00
- `DB` Refund does not create money: payer + payee total is unchanged
- … and 1 more

**BUG-06 — Contract drift on account balance.** balance returned as a JSON number (1000.5) instead of a string ("1000.50").

- `API` GET /accounts/{id} returns what was opened
- `API` Opening an account returns 201 with id, balance, INR and ACTIVE
- `Contract` verify(PactVerificationContext) arya-mobile-app - a request for an existing account
- `API` Balance is always a string with exactly two decimals

**BUG-07 — Lost update under concurrent transfers.** Balance is read outside the transaction and written back; parallel payments overwrite each other.

- `Concurrency` After parallel transfers the stored balances still match the ledger
- `BDD` A day of payments and a refund leaves the books balanced
- `DB` Every account's stored balance equals credits minus debits in the ledger
- `Concurrency` 25 parallel transfers of 10.00: every rupee is accounted for
- `Concurrency` 20 parallel transfers of 10.00 from 100.00: exactly 10 succeed, never negative

</details>

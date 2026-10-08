# Bug hunt

_Last run: 08 Oct 2026 10:02 UTC_

**Clean build:** 64/64 tests passed (no false alarms).  
**Planted bugs caught:** 7/7.  
**Release candidate with every bug on:** 26 of 64 tests fail.  
**Load (k6):** 1221 requests, p95 4 ms, 0.00% errors, money conserved.  

| Bug | What it does | Result | Caught by | Failing tests |
|---|---|---|---|---|
| BUG-01 | Idempotency key ignored | ✅ caught | API, DB, Concurrency | 4 |
| BUG-02 | Funds check compares whole rupees only | ✅ caught | API, DB | 4 |
| BUG-03 | Negative amounts accepted | ✅ caught | API, DB | 5 |
| BUG-04 | Transfer can be refunded twice | ✅ caught | API | 1 |
| BUG-05 | Refund never debits the receiver | ✅ caught | API, DB | 6 |
| BUG-06 | Contract drift on account balance | ✅ caught | API, Contract | 4 |
| BUG-07 | Lost update under concurrent transfers | ✅ caught | DB, Concurrency | 4 |

<details><summary>Which tests caught which bug</summary>

**BUG-01 — Idempotency key ignored.** A retried payment (same Idempotency-Key) debits the customer twice.

- `API` Retry with the same key returns the original transfer and debits once
- `Concurrency` 10 parallel retries with one key create exactly one transfer
- `API` Same key with a different amount is rejected with 409 and nothing moves
- `DB` A retried request leaves exactly one transfer row in the database

**BUG-02 — Funds check compares whole rupees only.** Balance 500.50 can send 500.90; the account goes negative.

- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.51 → 422 INSUFFICIENT_FUNDS
- `DB` No customer account is negative
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 0.00, send 0.01 → 422 INSUFFICIENT_FUNDS
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.90 → 422 INSUFFICIENT_FUNDS

**BUG-03 — Negative amounts accepted.** A transfer of -100.00 pulls money from the receiver.

- `API` Invalid opening balances are rejected with 422 INVALID_AMOUNT opening_balance "-5.00" is rejected
- `API` Invalid amounts are rejected and no money moves amount "-100.00" → 422 INVALID_AMOUNT, no money moves
- `DB` No ledger entry has a zero or negative amount
- `DB` No customer account is negative
- `API` Invalid amounts are rejected and no money moves amount "-0.01" → 422 INVALID_AMOUNT, no money moves

**BUG-04 — Transfer can be refunded twice.** Sender gets the money back twice.

- `API` A transfer cannot be refunded twice

**BUG-05 — Refund never debits the receiver.** Refund creates money; the double-entry ledger no longer balances.

- `DB` Every transaction's debits equal its credits
- `API` A transfer cannot be refunded twice
- `DB` Money is conserved: all balances (incl. the funding account) sum to 0.00
- `DB` Refund does not create money: payer + payee total is unchanged
- `API` Refund returns the money and marks the transfer REFUNDED
- `DB` Refund writes a balanced DEBIT (receiver) and CREDIT (payer) in the ledger

**BUG-06 — Contract drift on account balance.** balance returned as a JSON number (1000.5) instead of a string ("1000.50").

- `API` Opening an account returns 201 with id, balance, INR and ACTIVE
- `API` Balance is always a string with exactly two decimals
- `Contract` verify(PactVerificationContext) arya-mobile-app - a request for an existing account
- `API` GET /accounts/{id} returns what was opened

**BUG-07 — Lost update under concurrent transfers.** Balance is read outside the transaction and written back; parallel payments overwrite each other.

- `Concurrency` 20 parallel transfers of 10.00 from 100.00: exactly 10 succeed, never negative
- `Concurrency` After parallel transfers the stored balances still match the ledger
- `Concurrency` 25 parallel transfers of 10.00: every rupee is accounted for
- `DB` Every account's stored balance equals credits minus debits in the ledger

</details>

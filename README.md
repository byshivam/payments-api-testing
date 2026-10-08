# payments-api-testing

Java test suite for a fictional **Arya Bank** payments API, with deliberately planted bugs the suite has to catch.

The API (FastAPI + SQLite) is built in this repo so the tests can reach every layer: HTTP, contract, database and load. It is the same fictional bank as [banking-rag-eval](https://github.com/byshivam/banking-rag-eval). All data is synthetic.

**Live dashboard:** https://byshivam.github.io/payments-api-testing/

| Layer | Tooling | What it proves |
|---|---|---|
| BDD | Cucumber (Gherkin) + RestAssured + PicoContainer | Business flows in plain language: transfer, retries, refunds, end-of-day reconciliation |
| API | RestAssured + JUnit 5 | Status codes, error codes, money moves exactly, boundaries at the paisa |
| Idempotency | RestAssured + parallel threads | A retried payment debits once, even when 10 retries arrive together |
| Database | JDBC + SQLite | Double-entry ledger balances; stored balance = credits − debits; money is conserved |
| Concurrency | `ExecutorService` + `CountDownLatch` | No lost updates, no overdraft under parallel transfers |
| Contract | Pact JVM (consumer + provider) | The mobile app's expectations hold against the real API |
| Load | k6 | p95 latency, error rate, and money conservation under 40 transfers/s |
| Reporting | Allure + GitHub Pages | Per-test request/response attachments |

## Results

<!-- RESULTS:START -->
_Last run: 08 Oct 2026 12:09 UTC_

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

- `Concurrency` 10 parallel retries with one key create exactly one transfer
- `DB` A retried request leaves exactly one transfer row in the database
- `API` Same key with a different amount is rejected with 409 and nothing moves
- `API` Retry with the same key returns the original transfer and debits once

**BUG-02 — Funds check compares whole rupees only.** Balance 500.50 can send 500.90; the account goes negative.

- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 0.00, send 0.01 → 422 INSUFFICIENT_FUNDS
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.51 → 422 INSUFFICIENT_FUNDS
- `DB` No customer account is negative
- `API` Insufficient funds is rejected at the paisa boundary and no money moves balance 500.50, send 500.90 → 422 INSUFFICIENT_FUNDS

**BUG-03 — Negative amounts accepted.** A transfer of -100.00 pulls money from the receiver.

- `API` Invalid amounts are rejected and no money moves amount "-100.00" → 422 INVALID_AMOUNT, no money moves
- `API` Invalid amounts are rejected and no money moves amount "-0.01" → 422 INVALID_AMOUNT, no money moves
- `API` Invalid opening balances are rejected with 422 INVALID_AMOUNT opening_balance "-5.00" is rejected
- `DB` No ledger entry has a zero or negative amount
- `DB` No customer account is negative

**BUG-04 — Transfer can be refunded twice.** Sender gets the money back twice.

- `API` A transfer cannot be refunded twice

**BUG-05 — Refund never debits the receiver.** Refund creates money; the double-entry ledger no longer balances.

- `API` A transfer cannot be refunded twice
- `DB` Every transaction's debits equal its credits
- `API` Refund returns the money and marks the transfer REFUNDED
- `DB` Refund does not create money: payer + payee total is unchanged
- `DB` Money is conserved: all balances (incl. the funding account) sum to 0.00
- `DB` Refund writes a balanced DEBIT (receiver) and CREDIT (payer) in the ledger

**BUG-06 — Contract drift on account balance.** balance returned as a JSON number (1000.5) instead of a string ("1000.50").

- `API` GET /accounts/{id} returns what was opened
- `API` Opening an account returns 201 with id, balance, INR and ACTIVE
- `API` Balance is always a string with exactly two decimals
- `Contract` verify(PactVerificationContext) arya-mobile-app - a request for an existing account

**BUG-07 — Lost update under concurrent transfers.** Balance is read outside the transaction and written back; parallel payments overwrite each other.

- `Concurrency` 25 parallel transfers of 10.00: every rupee is accounted for
- `Concurrency` 20 parallel transfers of 10.00 from 100.00: exactly 10 succeed, never negative
- `DB` Every account's stored balance equals credits minus debits in the ledger
- `Concurrency` After parallel transfers the stored balances still match the ledger

</details>
<!-- RESULTS:END -->

## BDD scenarios

The business-critical flows are also written as Cucumber scenarios in [`src/test/resources/features`](src/test/resources/features), so a product owner can read what is covered:

```gherkin
Scenario: A retried payment is charged once
  When Asha sends 100.00 to Ravi with idempotency key "rent-october"
  And the app retries the same payment with idempotency key "rent-october"
  Then both responses show the same transfer
  And Asha's balance is 900.00
  And there is 1 transfer from Asha to Ravi
```

- **Test data:** every scenario creates its own accounts through the API in its `Given` steps; there is no shared fixture file. A `ScenarioContext` (injected by PicoContainer) maps the names in the scenario ("Asha", "Ravi") to the account ids the API returned, and makes idempotency keys unique per scenario.
- **Same building blocks:** the step definitions reuse the JUnit suite's API client and JDBC ledger queries, so `Then` steps check both the API and the database.
- **One run, one report:** the Cucumber engine runs on the JUnit Platform inside `mvn verify`, and scenarios appear in the same Allure report (feature → scenario → steps).
- **Where BDD stops:** edge-case matrices, concurrency and contract tests stay in plain JUnit, where they're shorter and clearer.

## How the bug hunt works

A test suite that has never failed hasn't proven anything. So the API ships with seven realistic payment defects, each behind a switch (`PLANTED_BUGS=BUG-03`). CI runs the whole suite:

1. once against the **clean build**: every test must pass (no false alarms), then
2. once **per planted bug**: at least one test must fail, otherwise the bug "escaped" and CI goes red, then
3. once with **every bug on**, as a "release candidate" report.

This is the idea behind mutation testing, applied to defects a payments team actually worries about.

| Bug | Defect | Why it matters |
|---|---|---|
| BUG-01 | Idempotency key ignored | A network retry debits the customer twice |
| BUG-02 | Funds check compares whole rupees only | Balance 500.50 can send 500.90; account goes negative |
| BUG-03 | Negative amounts accepted | A transfer of −100.00 pulls money from the receiver |
| BUG-04 | Transfer can be refunded twice | Sender is paid back twice |
| BUG-05 | Refund never debits the receiver | Money is created; the ledger no longer balances |
| BUG-06 | Contract drift on `balance` | Returned as a number (`1000.5`) instead of a string (`"1000.50"`); breaks the app |
| BUG-07 | Lost update under concurrency | Parallel payments overwrite each other's balance |

The bugs live in [`app/arya_payments/bugs.py`](app/arya_payments/bugs.py) and are off by default.

## The API under test

| Endpoint | Notes |
|---|---|
| `POST /accounts` | Opens an account; opening balance is funded through the ledger |
| `GET /accounts/{id}` | Balance as a string with two decimals |
| `GET /accounts/{id}/transactions` | Ledger entries, newest first |
| `POST /transfers` | Requires `Idempotency-Key`; limit ₹1,00,000; same key + same body replays with `200` |
| `GET /transfers/{id}` | |
| `POST /transfers/{id}/refund` | Full refund; `409` if already refunded |

Money is stored as integer paise and sent as strings (`"250.00"`). Errors are always `{"error": {"code", "message"}}`. Every movement writes one DEBIT and one CREDIT to `ledger_entries`, so the database can prove whether money was created or lost, independent of what the API says.

## Project layout

```
app/arya_payments/          FastAPI app (system under test) + planted bugs
src/test/resources/features/ Gherkin scenarios (transfers, retries, refunds, reconciliation)
src/test/java/com/aryabank/payments/
  bdd/                      Cucumber runner, step definitions, scenario context
  api/                      RestAssured tests: accounts, transfers, validation,
                            idempotency, refunds, concurrency
  db/                       Whole-database reconciliation (runs last)
  contract/                 Pact consumer test (mobile app) + provider verification
  support/                  API client, JDBC ledger queries, money assertions
load/transfers.js           k6 load test
scripts/bug_hunt.py         Runs clean + per-bug + all-bugs, writes the results
scripts/build_site.py       Dashboard for GitHub Pages
```

## Run it locally

Needs Java 17+, Maven, Python 3.10+ (k6 optional).

```bash
pip install -r app/requirements.txt

# whole bug hunt (about 8 suite runs)
python scripts/bug_hunt.py

# or by hand: start the API, then run the suite against it
ARYA_DB_PATH=$PWD/arya.db ARYA_TEST_HOOKS=1 \
  python -m uvicorn arya_payments.main:app --app-dir app --port 8000 &
ARYA_DB_PATH=$PWD/arya.db mvn verify

# try a bug
PLANTED_BUGS=BUG-01 ...   # same command as above

# load test
k6 run -e BASE_URL=http://localhost:8000 load/transfers.js
```

`ARYA_TEST_HOOKS=1` enables `POST /_test/accounts` (fixed-id accounts for Pact provider states). It is never on by default.

## Design choices

- **Tests in Java, API in Python.** The suite treats the API as a black box over HTTP and SQL, the way a QA team tests a service built by another team.
- **JUnit 5 instead of TestNG.** Pact JVM's first-class runner is JUnit 5, and one runner keeps the build simple.
- **Database checks next to API checks.** An API can answer `201` and still corrupt the ledger (BUG-05 does exactly that). The DB layer catches what the HTTP layer can't see.
- **Fresh accounts per test.** No shared fixtures, so tests can run in any order; only the reconciliation class is ordered last on purpose.

## CI

`.github/workflows/tests.yml` runs on every push, every PR, and nightly at 03:47 IST: k6 load test, then the bug hunt, then Allure reports and the dashboard to GitHub Pages. Nightly and manual runs also refresh the Results section above.

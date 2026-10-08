# payments-api-testing

Java test suite for a fictional **Arya Bank** payments API, with deliberately planted bugs the suite has to catch.

The API (FastAPI + SQLite) is built in this repo so the tests can reach every layer: HTTP, contract, database and load. It is the same fictional bank as [banking-rag-eval](https://github.com/byshivam/banking-rag-eval). All data is synthetic.

**Live dashboard:** https://byshivam.github.io/payments-api-testing/

| Layer | Tooling | What it proves |
|---|---|---|
| API | RestAssured + JUnit 5 | Status codes, error codes, money moves exactly, boundaries at the paisa |
| Idempotency | RestAssured + parallel threads | A retried payment debits once, even when 10 retries arrive together |
| Database | JDBC + SQLite | Double-entry ledger balances; stored balance = credits − debits; money is conserved |
| Concurrency | `ExecutorService` + `CountDownLatch` | No lost updates, no overdraft under parallel transfers |
| Contract | Pact JVM (consumer + provider) | The mobile app's expectations hold against the real API |
| Load | k6 | p95 latency, error rate, and money conservation under 40 transfers/s |
| Reporting | Allure + GitHub Pages | Per-test request/response attachments |

## Results

<!-- RESULTS:START -->
_Results appear here after the first scheduled or manual CI run._
<!-- RESULTS:END -->

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
src/test/java/com/aryabank/payments/
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

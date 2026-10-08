"""SQLite storage with a double-entry ledger.

Every movement of money writes one DEBIT and one CREDIT ledger entry of the
same amount under one txn_id. Opening balances are funded from the system
account AB000000, which is the only account allowed to go negative. That gives
the test suite two invariants to check straight in the database:

  1. For every txn_id, total DEBIT == total CREDIT.
  2. For every account, balance == sum(CREDIT) - sum(DEBIT).
"""

from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone

SYSTEM_ACCOUNT = "AB000000"

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    seq           INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id    TEXT UNIQUE,
    holder_name   TEXT NOT NULL,
    balance_paise INTEGER NOT NULL,
    currency      TEXT NOT NULL DEFAULT 'INR',
    status        TEXT NOT NULL DEFAULT 'ACTIVE',
    kind          TEXT NOT NULL DEFAULT 'CUSTOMER',
    created_at    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS transfers (
    transfer_id   TEXT PRIMARY KEY,
    kind          TEXT NOT NULL,              -- TRANSFER | REFUND
    from_account  TEXT NOT NULL,
    to_account    TEXT NOT NULL,
    amount_paise  INTEGER NOT NULL,
    status        TEXT NOT NULL,              -- COMPLETED | REFUNDED
    remarks       TEXT,
    refund_of     TEXT,
    refund_id     TEXT,
    created_at    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    txn_id        TEXT NOT NULL,
    account_id    TEXT NOT NULL,
    direction     TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_paise  INTEGER NOT NULL,
    created_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries(account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_txn ON ledger_entries(txn_id);
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idem_key      TEXT PRIMARY KEY,
    request_hash  TEXT NOT NULL,
    transfer_id   TEXT NOT NULL,
    created_at    TEXT NOT NULL
);
"""


def db_path() -> str:
    return os.environ.get("ARYA_DB_PATH", "arya_payments.db")


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(db_path(), timeout=15, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout = 15000")
    return conn


@contextmanager
def write_txn():
    """BEGIN IMMEDIATE takes the write lock up front, so writers are serialised."""
    conn = connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        yield conn
        conn.execute("COMMIT")
    except BaseException:
        if conn.in_transaction:
            conn.execute("ROLLBACK")
        raise
    finally:
        conn.close()


@contextmanager
def read_conn():
    conn = connect()
    try:
        yield conn
    finally:
        conn.close()


def init() -> None:
    conn = connect()
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.executescript(SCHEMA)
        exists = conn.execute(
            "SELECT 1 FROM accounts WHERE account_id = ?", (SYSTEM_ACCOUNT,)
        ).fetchone()
        if not exists:
            conn.execute(
                "INSERT INTO accounts (account_id, holder_name, balance_paise, kind, created_at) "
                "VALUES (?, 'Arya Bank Funding', 0, 'SYSTEM', ?)",
                (SYSTEM_ACCOUNT, now()),
            )
    finally:
        conn.close()


def post_entries(conn: sqlite3.Connection, txn_id: str, debit_acct: str, credit_acct: str, paise: int) -> None:
    ts = now()
    conn.execute(
        "INSERT INTO ledger_entries (txn_id, account_id, direction, amount_paise, created_at) VALUES (?, ?, 'DEBIT', ?, ?)",
        (txn_id, debit_acct, paise, ts),
    )
    conn.execute(
        "INSERT INTO ledger_entries (txn_id, account_id, direction, amount_paise, created_at) VALUES (?, ?, 'CREDIT', ?, ?)",
        (txn_id, credit_acct, paise, ts),
    )

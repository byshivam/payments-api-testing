"""Arya Bank payments API (fictional) — the system under test.

Endpoints
  GET  /health
  POST /accounts                     open an account with an opening balance
  GET  /accounts/{id}                balance and status
  GET  /accounts/{id}/transactions   ledger entries, newest first
  POST /transfers                    UPI-style transfer (Idempotency-Key header required)
  GET  /transfers/{id}
  POST /transfers/{id}/refund        full refund of a completed transfer
  POST /_test/accounts               test hook (only when ARYA_TEST_HOOKS=1)

Errors always look like {"error": {"code": "...", "message": "..."}}.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, StrictStr

from . import bugs, db
from .money import fmt, to_paise

MAX_TRANSFER_PAISE = 1_00_000_00  # ₹1,00,000 per transfer (UPI-style limit)
MAX_OPENING_PAISE = 1_00_00_000_00  # ₹1 crore


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    yield


app = FastAPI(title="Arya Bank Payments API", version="1.0.0", lifespan=lifespan)


@app.exception_handler(ApiError)
async def _api_error(_: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})


@app.exception_handler(RequestValidationError)
async def _validation_error(_: Request, exc: RequestValidationError):
    first = exc.errors()[0] if exc.errors() else {}
    field = ".".join(str(p) for p in first.get("loc", []) if p != "body") or "body"
    return JSONResponse(
        status_code=422,
        content={"error": {"code": "VALIDATION_ERROR", "message": f"{field}: {first.get('msg', 'invalid request')}"}},
    )


# ---------------------------------------------------------------- models


class OpenAccount(BaseModel):
    holder_name: StrictStr
    opening_balance: StrictStr = "0.00"


class TransferRequest(BaseModel):
    from_account: StrictStr
    to_account: StrictStr
    amount: StrictStr
    remarks: StrictStr | None = None


class TestAccount(BaseModel):
    account_id: StrictStr
    holder_name: StrictStr
    balance: StrictStr


# ---------------------------------------------------------------- helpers


def account_json(row) -> dict:
    balance: str | float = fmt(row["balance_paise"])
    if bugs.on("BUG-06"):
        balance = row["balance_paise"] / 100
    return {
        "account_id": row["account_id"],
        "holder_name": row["holder_name"],
        "balance": balance,
        "currency": row["currency"],
        "status": row["status"],
        "created_at": row["created_at"],
    }


def transfer_json(row) -> dict:
    return {
        "transfer_id": row["transfer_id"],
        "from_account": row["from_account"],
        "to_account": row["to_account"],
        "amount": fmt(row["amount_paise"]),
        "currency": "INR",
        "status": row["status"],
        "remarks": row["remarks"],
        "refund_id": row["refund_id"],
        "created_at": row["created_at"],
    }


def get_account(conn, account_id: str):
    return conn.execute(
        "SELECT * FROM accounts WHERE account_id = ? AND kind = 'CUSTOMER'", (account_id,)
    ).fetchone()


def parse_amount(text: str, field: str, *, allow_zero: bool, limit: int) -> int:
    paise = to_paise(text)
    if paise is None:
        raise ApiError(422, "INVALID_AMOUNT", f"{field} must be a string like \"250.00\" with at most 2 decimals")
    if paise == 0 and not allow_zero:
        raise ApiError(422, "INVALID_AMOUNT", f"{field} must be greater than 0.00")
    if paise > limit:
        raise ApiError(422, "LIMIT_EXCEEDED", f"{field} exceeds the limit of {fmt(limit)}")
    return paise


def new_id(prefix: str) -> str:
    return prefix + uuid.uuid4().hex[:12].upper()


# ---------------------------------------------------------------- routes


@app.get("/health")
def health():
    return {"status": "UP", "planted_bugs": sorted(bugs.ACTIVE)}


@app.post("/accounts", status_code=201)
def open_account(body: OpenAccount):
    name = body.holder_name.strip()
    if not 1 <= len(name) <= 80:
        raise ApiError(422, "VALIDATION_ERROR", "holder_name must be 1-80 characters")
    paise = parse_amount(body.opening_balance, "opening_balance", allow_zero=True, limit=MAX_OPENING_PAISE)
    with db.write_txn() as conn:
        cur = conn.execute(
            "INSERT INTO accounts (holder_name, balance_paise, created_at) VALUES (?, 0, ?)", (name, db.now())
        )
        account_id = f"AB{cur.lastrowid:06d}"
        conn.execute("UPDATE accounts SET account_id = ? WHERE seq = ?", (account_id, cur.lastrowid))
        if paise:
            conn.execute("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", (paise, account_id))
            conn.execute(
                "UPDATE accounts SET balance_paise = balance_paise - ? WHERE account_id = ?", (paise, db.SYSTEM_ACCOUNT)
            )
            db.post_entries(conn, f"OPEN-{account_id}", db.SYSTEM_ACCOUNT, account_id, paise)
        row = get_account(conn, account_id)
    return account_json(row)


@app.get("/accounts/{account_id}")
def read_account(account_id: str):
    with db.read_conn() as conn:
        row = get_account(conn, account_id)
    if not row:
        raise ApiError(404, "ACCOUNT_NOT_FOUND", f"Account {account_id} not found")
    return account_json(row)


@app.get("/accounts/{account_id}/transactions")
def account_transactions(account_id: str):
    with db.read_conn() as conn:
        if not get_account(conn, account_id):
            raise ApiError(404, "ACCOUNT_NOT_FOUND", f"Account {account_id} not found")
        rows = conn.execute(
            "SELECT txn_id, direction, amount_paise, created_at FROM ledger_entries "
            "WHERE account_id = ? ORDER BY entry_id DESC",
            (account_id,),
        ).fetchall()
    return {
        "account_id": account_id,
        "transactions": [
            {"txn_id": r["txn_id"], "direction": r["direction"], "amount": fmt(r["amount_paise"]), "created_at": r["created_at"]}
            for r in rows
        ],
    }


@app.post("/transfers", status_code=201)
def create_transfer(body: TransferRequest, idempotency_key: str | None = Header(default=None)):
    if not idempotency_key or not idempotency_key.strip():
        raise ApiError(400, "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required")
    if len(idempotency_key) > 100:
        raise ApiError(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be at most 100 characters")
    paise = parse_amount(body.amount, "amount", allow_zero=False, limit=MAX_TRANSFER_PAISE)
    if body.from_account == body.to_account:
        raise ApiError(422, "SAME_ACCOUNT", "from_account and to_account must be different")
    if body.remarks is not None and len(body.remarks) > 50:
        raise ApiError(422, "VALIDATION_ERROR", "remarks must be at most 50 characters")

    request_hash = hashlib.sha256(
        json.dumps([body.from_account, body.to_account, paise, body.remarks]).encode()
    ).hexdigest()

    stale = None
    if bugs.on("BUG-07"):
        # BUG-07: balances read before taking the write lock ...
        with db.read_conn() as conn:
            src, dst = get_account(conn, body.from_account), get_account(conn, body.to_account)
            if src and dst:
                stale = (src["balance_paise"], dst["balance_paise"])
        time.sleep(0.03)

    with db.write_txn() as conn:
        if not bugs.on("BUG-01"):
            seen = conn.execute("SELECT * FROM idempotency_keys WHERE idem_key = ?", (idempotency_key,)).fetchone()
            if seen:
                if seen["request_hash"] != request_hash:
                    raise ApiError(409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request")
                original = conn.execute("SELECT * FROM transfers WHERE transfer_id = ?", (seen["transfer_id"],)).fetchone()
                return JSONResponse(status_code=200, content=transfer_json(original), headers={"Idempotent-Replayed": "true"})

        src, dst = get_account(conn, body.from_account), get_account(conn, body.to_account)
        for acct_id, row in ((body.from_account, src), (body.to_account, dst)):
            if not row:
                raise ApiError(404, "ACCOUNT_NOT_FOUND", f"Account {acct_id} not found")

        src_bal, dst_bal = stale if stale else (src["balance_paise"], dst["balance_paise"])
        if bugs.on("BUG-02"):
            has_funds = src_bal // 100 >= paise // 100
        else:
            has_funds = src_bal >= paise
        if not has_funds:
            raise ApiError(422, "INSUFFICIENT_FUNDS", f"Insufficient funds in {body.from_account}")

        if stale:
            # ... and written back as absolute values, overwriting parallel updates.
            conn.execute("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", (src_bal - paise, body.from_account))
            conn.execute("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", (dst_bal + paise, body.to_account))
        else:
            conn.execute("UPDATE accounts SET balance_paise = balance_paise - ? WHERE account_id = ?", (paise, body.from_account))
            conn.execute("UPDATE accounts SET balance_paise = balance_paise + ? WHERE account_id = ?", (paise, body.to_account))

        transfer_id = new_id("TXN")
        conn.execute(
            "INSERT INTO transfers (transfer_id, kind, from_account, to_account, amount_paise, status, remarks, created_at) "
            "VALUES (?, 'TRANSFER', ?, ?, ?, 'COMPLETED', ?, ?)",
            (transfer_id, body.from_account, body.to_account, paise, body.remarks, db.now()),
        )
        db.post_entries(conn, transfer_id, body.from_account, body.to_account, paise)
        if not bugs.on("BUG-01"):
            conn.execute(
                "INSERT INTO idempotency_keys (idem_key, request_hash, transfer_id, created_at) VALUES (?, ?, ?, ?)",
                (idempotency_key, request_hash, transfer_id, db.now()),
            )
        row = conn.execute("SELECT * FROM transfers WHERE transfer_id = ?", (transfer_id,)).fetchone()
    return transfer_json(row)


@app.get("/transfers/{transfer_id}")
def read_transfer(transfer_id: str):
    with db.read_conn() as conn:
        row = conn.execute(
            "SELECT * FROM transfers WHERE transfer_id = ? AND kind = 'TRANSFER'", (transfer_id,)
        ).fetchone()
    if not row:
        raise ApiError(404, "TRANSFER_NOT_FOUND", f"Transfer {transfer_id} not found")
    return transfer_json(row)


@app.post("/transfers/{transfer_id}/refund", status_code=201)
def refund_transfer(transfer_id: str):
    with db.write_txn() as conn:
        original = conn.execute(
            "SELECT * FROM transfers WHERE transfer_id = ? AND kind = 'TRANSFER'", (transfer_id,)
        ).fetchone()
        if not original:
            raise ApiError(404, "TRANSFER_NOT_FOUND", f"Transfer {transfer_id} not found")
        if original["status"] == "REFUNDED" and not bugs.on("BUG-04"):
            raise ApiError(409, "ALREADY_REFUNDED", f"Transfer {transfer_id} was already refunded")

        paise = original["amount_paise"]
        payer, payee = original["from_account"], original["to_account"]
        receiver = get_account(conn, payee)
        if receiver["balance_paise"] < paise:
            raise ApiError(422, "INSUFFICIENT_FUNDS", f"Insufficient funds in {payee} to refund")

        refund_id = new_id("RFD")
        conn.execute("UPDATE accounts SET balance_paise = balance_paise + ? WHERE account_id = ?", (paise, payer))
        if bugs.on("BUG-05"):
            # BUG-05: receiver is never debited; only the CREDIT leg is written.
            ts = db.now()
            conn.execute(
                "INSERT INTO ledger_entries (txn_id, account_id, direction, amount_paise, created_at) VALUES (?, ?, 'CREDIT', ?, ?)",
                (refund_id, payer, paise, ts),
            )
        else:
            conn.execute("UPDATE accounts SET balance_paise = balance_paise - ? WHERE account_id = ?", (paise, payee))
            db.post_entries(conn, refund_id, payee, payer, paise)

        ts = db.now()
        conn.execute(
            "INSERT INTO transfers (transfer_id, kind, from_account, to_account, amount_paise, status, refund_of, created_at) "
            "VALUES (?, 'REFUND', ?, ?, ?, 'COMPLETED', ?, ?)",
            (refund_id, payee, payer, paise, transfer_id, ts),
        )
        conn.execute(
            "UPDATE transfers SET status = 'REFUNDED', refund_id = ? WHERE transfer_id = ?", (refund_id, transfer_id)
        )
    return {
        "refund_id": refund_id,
        "transfer_id": transfer_id,
        "amount": fmt(paise),
        "currency": "INR",
        "status": "COMPLETED",
        "created_at": ts,
    }


# ---------------------------------------------------------------- test hooks

if os.environ.get("ARYA_TEST_HOOKS") == "1":

    @app.post("/_test/accounts", status_code=201)
    def seed_account(body: TestAccount):
        """Create or reset an account with a fixed id (used for Pact provider states).

        Balance changes still go through the ledger (against the system account),
        so the double-entry invariants keep holding.
        """
        target = to_paise(body.balance)
        if target is None or target < 0:
            raise ApiError(422, "INVALID_AMOUNT", "balance must be a non-negative amount")
        with db.write_txn() as conn:
            row = get_account(conn, body.account_id)
            if not row:
                conn.execute(
                    "INSERT INTO accounts (account_id, holder_name, balance_paise, created_at) VALUES (?, ?, 0, ?)",
                    (body.account_id, body.holder_name, db.now()),
                )
                current = 0
            else:
                current = row["balance_paise"]
                conn.execute(
                    "UPDATE accounts SET holder_name = ? WHERE account_id = ?", (body.holder_name, body.account_id)
                )
            delta = target - current
            if delta:
                conn.execute("UPDATE accounts SET balance_paise = ? WHERE account_id = ?", (target, body.account_id))
                conn.execute(
                    "UPDATE accounts SET balance_paise = balance_paise - ? WHERE account_id = ?", (delta, db.SYSTEM_ACCOUNT)
                )
                adj = new_id("ADJ")
                if delta > 0:
                    db.post_entries(conn, adj, db.SYSTEM_ACCOUNT, body.account_id, delta)
                else:
                    db.post_entries(conn, adj, body.account_id, db.SYSTEM_ACCOUNT, -delta)
            row = get_account(conn, body.account_id)
        return account_json(row)

    @app.delete("/_test/idempotency-keys/{key}", status_code=204)
    def forget_idempotency_key(key: str):
        """Lets a Pact provider state replay a contract that uses a fixed Idempotency-Key."""
        with db.write_txn() as conn:
            conn.execute("DELETE FROM idempotency_keys WHERE idem_key = ?", (key,))

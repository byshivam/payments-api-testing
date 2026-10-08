"""Planted bugs.

The API is the system under test, so it ships with deliberate defects that
the Java suite has to catch. Each bug is off by default and switched on with
the PLANTED_BUGS environment variable:

    PLANTED_BUGS=none            # clean build (default)
    PLANTED_BUGS=BUG-01,BUG-04   # only these
    PLANTED_BUGS=all             # the "release candidate" with every defect

The bug-hunt runner (scripts/bug_hunt.py) starts the API once per bug and
checks that the suite fails. A bug that survives means a gap in the tests.
"""

from __future__ import annotations

import os

CATALOG: dict[str, dict[str, str]] = {
    "BUG-01": {
        "title": "Idempotency key ignored",
        "impact": "A retried payment (same Idempotency-Key) debits the customer twice.",
    },
    "BUG-02": {
        "title": "Funds check compares whole rupees only",
        "impact": "Balance 500.50 can send 500.90; the account goes negative.",
    },
    "BUG-03": {
        "title": "Negative amounts accepted",
        "impact": "A transfer of -100.00 pulls money from the receiver.",
    },
    "BUG-04": {
        "title": "Transfer can be refunded twice",
        "impact": "Sender gets the money back twice.",
    },
    "BUG-05": {
        "title": "Refund never debits the receiver",
        "impact": "Refund creates money; the double-entry ledger no longer balances.",
    },
    "BUG-06": {
        "title": "Contract drift on account balance",
        "impact": "balance returned as a JSON number (1000.5) instead of a string (\"1000.50\").",
    },
    "BUG-07": {
        "title": "Lost update under concurrent transfers",
        "impact": "Balance is read outside the transaction and written back; parallel payments overwrite each other.",
    },
}


def _parse(raw: str | None) -> frozenset[str]:
    value = (raw or "none").strip()
    if value.lower() in ("", "none", "off", "0"):
        return frozenset()
    if value.lower() == "all":
        return frozenset(CATALOG)
    ids = {part.strip().upper() for part in value.split(",") if part.strip()}
    unknown = ids - set(CATALOG)
    if unknown:
        raise ValueError(f"Unknown planted bug id(s): {', '.join(sorted(unknown))}")
    return frozenset(ids)


ACTIVE: frozenset[str] = _parse(os.environ.get("PLANTED_BUGS"))


def on(bug_id: str) -> bool:
    return bug_id in ACTIVE

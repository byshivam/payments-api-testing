"""Money is stored as integer paise and sent over the wire as a string, e.g. "250.00"."""

from __future__ import annotations

import re

from . import bugs

AMOUNT_RE = re.compile(r"^\d{1,9}(\.\d{1,2})?$")
BUGGY_AMOUNT_RE = re.compile(r"^-?\d{1,9}(\.\d{1,2})?$")  # BUG-03


def to_paise(text: str) -> int | None:
    """Parse "250.5" -> 25050. Returns None if the format is not valid."""
    pattern = BUGGY_AMOUNT_RE if bugs.on("BUG-03") else AMOUNT_RE
    if not isinstance(text, str) or not pattern.match(text):
        return None
    negative = text.startswith("-")
    rupees, _, frac = text.lstrip("-").partition(".")
    paise = int(rupees) * 100 + int((frac + "00")[:2])
    return -paise if negative else paise


def fmt(paise: int) -> str:
    sign = "-" if paise < 0 else ""
    paise = abs(paise)
    return f"{sign}{paise // 100}.{paise % 100:02d}"

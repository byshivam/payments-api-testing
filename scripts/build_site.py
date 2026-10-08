"""Build site/index.html: the bug-hunt dashboard published on GitHub Pages.

Reads reports/runs.json (written by bug_hunt.py) and reports/k6-summary.json,
and links the Allure reports generated into site/clean and site/all.
"""

from __future__ import annotations

import html
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "app"))
sys.path.insert(0, str(ROOT / "scripts"))
from arya_payments.bugs import CATALOG  # noqa: E402
from bug_hunt import load_k6  # noqa: E402

SITE = ROOT / "site"

CSS = """
:root{--bg:#f7f7f5;--card:#fff;--ink:#1d2433;--muted:#5d6678;--line:#e3e5ea;--ok:#1f7a4d;--okbg:#e5f4ec;
--bad:#b4232a;--badbg:#fbe9ea;--warn:#8a5a00;--warnbg:#fdf3dc;--accent:#2b4c9b}
@media (prefers-color-scheme:dark){:root{--bg:#12151c;--card:#1a1f29;--ink:#e6e9ef;--muted:#9aa3b5;--line:#2a3140;
--ok:#5fd39a;--okbg:#173327;--bad:#ff8a8f;--badbg:#3a1c1f;--warn:#f2c066;--warnbg:#3a2f15;--accent:#8fb0ff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:980px;margin:0 auto;padding:32px 16px 64px}h1{font-size:26px;margin:0 0 4px}h2{font-size:18px;margin:36px 0 12px}
.sub{color:var(--muted);margin:0 0 24px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px}
.big{font-size:28px;font-weight:650;font-variant-numeric:tabular-nums}.label{color:var(--muted);font-size:13px}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}
th,td{text-align:left;padding:10px 12px;border-bottom:1px solid var(--line);vertical-align:top}th{font-size:13px;color:var(--muted);font-weight:600}
tr:last-child td{border-bottom:0}.pill{display:inline-block;padding:2px 8px;border-radius:99px;font-size:12px;font-weight:600;white-space:nowrap}
.ok{color:var(--ok);background:var(--okbg)}.bad{color:var(--bad);background:var(--badbg)}.warn{color:var(--warn);background:var(--warnbg)}
.muted{color:var(--muted);font-size:13px}a{color:var(--accent)}ul{margin:6px 0 0;padding-left:18px}code{font-size:13px}
.wrap{overflow-x:auto}
"""


def pill(text: str, kind: str) -> str:
    return f'<span class="pill {kind}">{html.escape(text)}</span>'


def main() -> None:
    runs = json.loads((ROOT / "reports" / "runs.json").read_text()) if (ROOT / "reports" / "runs.json").exists() else []
    by_run = {r["run"]: r for r in runs}
    bug_runs = [r for r in runs if r["run"].startswith("BUG-")]
    caught = sum(r.get("verdict") == "CAUGHT" for r in bug_runs)
    clean = by_run.get("clean")
    rc = by_run.get("all")
    k6 = load_k6()
    stamp = datetime.now(timezone.utc).strftime("%d %b %Y, %H:%M UTC")

    cards = []
    if clean:
        kind = "ok" if clean.get("verdict") == "PASS" else "bad"
        cards.append(f'<div class="card"><div class="label">Clean build</div><div class="big">{clean["passed"]}/{clean["total"]}</div>'
                     f'{pill("all tests pass" if kind == "ok" else clean.get("verdict", "?"), kind)}</div>')
    if bug_runs:
        kind = "ok" if caught == len(bug_runs) else "bad"
        cards.append(f'<div class="card"><div class="label">Planted bugs caught</div><div class="big">{caught}/{len(bug_runs)}</div>'
                     f'{pill("none escaped" if kind == "ok" else "bug escaped", kind)}</div>')
    if rc:
        cards.append(f'<div class="card"><div class="label">Release candidate (all bugs on)</div><div class="big">{rc["failed"]}</div>'
                     f'<span class="muted">of {rc["total"]} tests fail</span></div>')
    if k6:
        cards.append(f'<div class="card"><div class="label">Load test (k6)</div><div style="margin-top:6px">{html.escape(k6["summary"])}</div></div>')

    rows = []
    for r in bug_runs:
        info = CATALOG[r["run"]]
        v = r.get("verdict")
        status = pill("caught", "ok") if v == "CAUGHT" else pill("escaped", "bad") if v == "ESCAPED" else pill("error", "warn")
        tests = "".join(f"<li><code>{html.escape(t['layer'])}</code> {html.escape(t['name'])}</li>" for t in r["failed_tests"][:6])
        more = f'<li class="muted">… and {len(r["failed_tests"]) - 6} more</li>' if len(r["failed_tests"]) > 6 else ""
        rows.append(
            f"<tr><td><strong>{r['run']}</strong></td>"
            f"<td><strong>{html.escape(info['title'])}</strong><div class='muted'>{html.escape(info['impact'])}</div></td>"
            f"<td>{status}<div class='muted' style='margin-top:4px'>{html.escape(', '.join(r['failed_layers']) or '—')}</div></td>"
            f"<td>{r['failed']} failing<ul>{tests}{more}</ul></td></tr>"
        )

    links = []
    if (SITE / "clean" / "index.html").exists():
        links.append('<a href="clean/index.html">Allure report — clean build</a>')
    if (SITE / "all" / "index.html").exists():
        links.append('<a href="all/index.html">Allure report — release candidate with every bug on</a>')

    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Payments API Tests</title><style>{CSS}</style></head>
<body><main>
<h1>Arya Bank payments API — test results</h1>
<p class="sub">Fictional bank, synthetic data. Java (RestAssured + JUnit 5) suite run against a FastAPI + SQLite payments API,
once clean and once per planted bug. Updated {stamp}.</p>
<div class="grid">{''.join(cards)}</div>
<h2>Planted bugs</h2>
<p class="muted">Each run starts a fresh API with exactly one bug switched on. A bug counts as caught when at least one test fails.</p>
<div class="wrap"><table><thead><tr><th>Bug</th><th>Defect</th><th>Result / layer</th><th>Tests that caught it</th></tr></thead>
<tbody>{''.join(rows) or '<tr><td colspan="4">No bug runs yet.</td></tr>'}</tbody></table></div>
<h2>Detailed reports</h2>
<p>{' · '.join(links) or '<span class="muted">Allure reports not generated in this run.</span>'}</p>
<p class="muted"><a href="https://github.com/byshivam/payments-api-testing">Source on GitHub</a></p>
</main></body></html>"""
    SITE.mkdir(exist_ok=True)
    (SITE / "index.html").write_text(page, encoding="utf-8")
    (SITE / ".nojekyll").write_text("")


if __name__ == "__main__":
    main()

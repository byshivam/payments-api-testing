"""Run the Java suite against the API with each planted bug switched on.

    python scripts/bug_hunt.py                 # clean + every bug + all bugs
    python scripts/bug_hunt.py --runs clean    # just the clean build
    python scripts/bug_hunt.py --runs clean,BUG-03

For every run the script starts a fresh API (new SQLite file), runs
`mvn verify`, and reads the Allure results. It then checks two things:

  * clean build  -> every test passes (no false alarms)
  * each bug     -> at least one test fails (the bug is caught)

A bug that no test catches is an "escaped" bug and fails the script, the same
way a mutation-testing tool reports a surviving mutant.

Outputs: reports/summary.json, reports/BUG_HUNT.md, the README results block,
and reports/allure-results/<run>/ for the Allure HTML report.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "app"))
from arya_payments.bugs import CATALOG  # noqa: E402

WORK = ROOT / "work"
REPORTS = ROOT / "reports"
TARGET = ROOT / "target"
README = ROOT / "README.md"
START, END = "<!-- RESULTS:START -->", "<!-- RESULTS:END -->"
LAYER_ORDER = ["BDD", "API", "DB", "Concurrency", "Contract"]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_healthy(url: str, proc: subprocess.Popen, timeout: float = 30) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"API exited early with code {proc.returncode}")
        try:
            with urllib.request.urlopen(url + "/health", timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(0.3)
    raise RuntimeError("API did not become healthy in time")


def layer_of(result: dict) -> str:
    labels = result.get("labels", [])
    tags = {l["value"].lower().lstrip("@") for l in labels if l.get("name") == "tag"}
    full = result.get("fullName", "")
    framework = " ".join(l["value"].lower() for l in labels if l.get("name") == "framework")
    if "bdd" in tags or "cucumber" in framework:
        return "BDD"
    if "concurrency" in tags or "ConcurrencyTest" in full:
        return "Concurrency"
    if "contract" in tags or ".contract." in full:
        return "Contract"
    if "db" in tags or ".db." in full:
        return "DB"
    return "API"


def read_allure(results_dir: Path) -> list[dict]:
    tests = []
    for f in sorted(results_dir.glob("*-result.json")):
        data = json.loads(f.read_text(encoding="utf-8"))
        status = data.get("status", "unknown")
        params = ", ".join(p.get("value", "") for p in data.get("parameters", []) if p.get("name") != "UniqueId")
        name = data.get("name", "?")
        if params and params not in name:
            name = f"{name} [{params}]"
        tests.append({
            "name": name,
            "full_name": data.get("fullName", ""),
            "layer": layer_of(data),
            "status": status,
            "message": ((data.get("statusDetails") or {}).get("message") or "").strip().splitlines()[0:1],
        })
    for t in tests:
        t["message"] = t["message"][0][:300] if t["message"] else ""
    return tests


def run_once(run: str, bugs: str) -> dict:
    WORK.mkdir(exist_ok=True)
    for sub in ("allure-results", "surefire-reports", "failsafe-reports", "pacts"):
        shutil.rmtree(TARGET / sub, ignore_errors=True)
    db_file = WORK / f"{run}.db"
    for suffix in ("", "-wal", "-shm"):
        Path(str(db_file) + suffix).unlink(missing_ok=True)

    port = free_port()
    base_url = f"http://127.0.0.1:{port}"
    env = {**os.environ, "ARYA_DB_PATH": str(db_file), "PLANTED_BUGS": bugs, "ARYA_TEST_HOOKS": "1", "ARYA_BASE_URL": base_url}
    api_log = open(WORK / f"{run}-api.log", "w")
    api = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "arya_payments.main:app", "--app-dir", str(ROOT / "app"),
         "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"],
        env=env, stdout=api_log, stderr=subprocess.STDOUT,
    )
    started = time.time()
    try:
        wait_healthy(base_url, api)
        with open(WORK / f"{run}-mvn.log", "w") as mvn_log:
            mvn = subprocess.run(
                ["mvn", "-B", "-ntp", "verify", "-Dmaven.test.failure.ignore=true"],
                cwd=ROOT, env=env, stdout=mvn_log, stderr=subprocess.STDOUT,
            )
    finally:
        api.terminate()
        try:
            api.wait(10)
        except subprocess.TimeoutExpired:
            api.kill()
        api_log.close()

    tests = read_allure(TARGET / "allure-results") if (TARGET / "allure-results").exists() else []
    keep = REPORTS / "allure-results" / run
    shutil.rmtree(keep, ignore_errors=True)
    if (TARGET / "allure-results").exists():
        shutil.copytree(TARGET / "allure-results", keep)

    failed = [t for t in tests if t["status"] in ("failed", "broken")]
    return {
        "run": run,
        "planted_bugs": bugs,
        "maven_exit": mvn.returncode,
        "duration_s": round(time.time() - started, 1),
        "total": len(tests),
        "passed": sum(t["status"] == "passed" for t in tests),
        "failed": len(failed),
        "skipped": sum(t["status"] == "skipped" for t in tests),
        "failed_tests": failed,
        "failed_layers": [l for l in LAYER_ORDER if any(t["layer"] == l for t in failed)],
        "tests": tests,
    }


def verdict(r: dict) -> str:
    if r["total"] == 0 or r["maven_exit"] != 0:
        return "ERROR"
    if r["run"] == "clean":
        return "PASS" if r["failed"] == 0 else "FALSE ALARM"
    if r["run"] == "all":
        return "CAUGHT" if r["failed"] else "ESCAPED"
    return "CAUGHT" if r["failed"] else "ESCAPED"


def render_markdown(results: list[dict], k6: dict | None) -> str:
    by_run = {r["run"]: r for r in results}
    lines = []
    stamp = datetime.now(timezone.utc).strftime("%d %b %Y %H:%M UTC")
    clean = by_run.get("clean")
    bug_runs = [r for r in results if r["run"].startswith("BUG-")]
    caught = sum(verdict(r) == "CAUGHT" for r in bug_runs)

    lines.append(f"_Last run: {stamp}_\n")
    if clean:
        lines.append(f"**Clean build:** {clean['passed']}/{clean['total']} tests passed "
                     f"({'no false alarms' if verdict(clean) == 'PASS' else verdict(clean)}).  ")
    if bug_runs:
        lines.append(f"**Planted bugs caught:** {caught}/{len(bug_runs)}.  ")
    if "all" in by_run:
        a = by_run["all"]
        lines.append(f"**Release candidate with every bug on:** {a['failed']} of {a['total']} tests fail.  ")
    if k6:
        lines.append(f"**Load (k6):** {k6['summary']}.  ")
    lines.append("")

    if bug_runs:
        lines.append("| Bug | What it does | Result | Caught by | Failing tests |")
        lines.append("|---|---|---|---|---|")
        for r in bug_runs:
            info = CATALOG[r["run"]]
            v = verdict(r)
            mark = "✅ caught" if v == "CAUGHT" else ("❌ escaped" if v == "ESCAPED" else "⚠️ error")
            lines.append(f"| {r['run']} | {info['title']} | {mark} | {', '.join(r['failed_layers']) or '—'} | {r['failed']} |")
        lines.append("")

        lines.append("<details><summary>Which tests caught which bug</summary>\n")
        for r in bug_runs:
            lines.append(f"**{r['run']} — {CATALOG[r['run']]['title']}.** {CATALOG[r['run']]['impact']}\n")
            for t in r["failed_tests"][:8]:
                lines.append(f"- `{t['layer']}` {t['name']}")
            if len(r["failed_tests"]) > 8:
                lines.append(f"- … and {len(r['failed_tests']) - 8} more")
            lines.append("")
        lines.append("</details>")
    return "\n".join(lines)


def load_k6() -> dict | None:
    path = REPORTS / "k6-summary.json"
    if not path.exists():
        return None
    data = json.loads(path.read_text())
    m = data.get("metrics", {})
    dur = m.get("http_req_duration{name:transfer}") or m.get("http_req_duration", {})
    reqs = m.get("http_reqs", {})
    failed = m.get("http_req_failed", {})
    conservation = m.get("money_conservation_failures", {})
    p95 = dur.get("p(95)")
    parts = []
    if reqs.get("count") is not None:
        parts.append(f"{int(reqs['count'])} requests")
    if p95 is not None:
        parts.append(f"p95 {p95:.0f} ms")
    if failed.get("value") is not None:
        parts.append(f"{failed['value'] * 100:.2f}% errors")
    if conservation:
        parts.append("money conserved" if conservation.get("count", 0) == 0 else "money NOT conserved")
    return {"summary": ", ".join(parts)} if parts else None


def update_readme(block: str) -> None:
    if not README.exists():
        return
    text = README.read_text(encoding="utf-8")
    if START not in text or END not in text:
        return
    head, rest = text.split(START, 1)
    _, tail = rest.split(END, 1)
    README.write_text(f"{head}{START}\n{block}\n{END}{tail}", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", default="clean," + ",".join(CATALOG) + ",all")
    parser.add_argument("--update-readme", action="store_true")
    args = parser.parse_args()

    runs = [r.strip() for r in args.runs.split(",") if r.strip()]
    REPORTS.mkdir(exist_ok=True)
    results = []
    for run in runs:
        bugs = "none" if run == "clean" else run
        print(f"▶ {run} (PLANTED_BUGS={bugs}) ...", flush=True)
        r = run_once(run, bugs)
        r["verdict"] = verdict(r)
        print(f"  {r['verdict']}: {r['passed']} passed, {r['failed']} failed of {r['total']} "
              f"in {r['duration_s']}s {r['failed_layers'] or ''}", flush=True)
        if r["verdict"] == "ERROR":
            print(f"  maven exit {r['maven_exit']}; see work/{run}-mvn.log", flush=True)
        results.append(r)

    summary = [{k: v for k, v in r.items() if k != "tests"} for r in results]
    (REPORTS / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (REPORTS / "runs.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    block = render_markdown(results, load_k6())
    (REPORTS / "BUG_HUNT.md").write_text("# Bug hunt\n\n" + block + "\n", encoding="utf-8")
    if args.update_readme:
        update_readme(block)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("## Bug hunt\n\n" + block + "\n")

    bad = [r["run"] for r in results if r["verdict"] in ("ERROR", "FALSE ALARM", "ESCAPED")]
    if bad:
        print(f"✗ Problems in: {', '.join(bad)}")
        return 1
    print("✓ Clean build passes and every planted bug was caught.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

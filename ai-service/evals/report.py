
import json
import sys
from pathlib import Path

from evals.checks import check
from evals.judge import judge
from evals.runner import load_scenarios

OUT = Path(__file__).parent / "out"
THRESHOLD = 0.85


def main() -> None:
    scenarios = {s["id"]: s for s in load_scenarios()}
    rows, passed = [], 0
    for sc_id, sc in scenarios.items():
        path = OUT / f"{sc_id}.json"
        if not path.exists():
            rows.append((sc_id, "MISSING", "run evals.runner first"))
            continue
        rec = json.loads(path.read_text(encoding="utf-8"))
        fails = check(sc, rec)
        verdict = None
        if not fails and sc.get("judge_rubric") and rec["answer"]:
            verdict = judge(rec, sc["judge_rubric"])
            if not verdict.passed:
                fails.append(f"judge: {verdict.reason} "
                             f"(corr={verdict.correctness}, grnd={verdict.groundedness})")
        ok = not fails
        passed += ok
        rows.append((sc_id, "PASS" if ok else "FAIL", "; ".join(fails)))

    width = max(len(r[0]) for r in rows)
    for sc_id, status, detail in rows:
        print(f"{sc_id:<{width}}  {status:4}  {detail}")
    rate = passed / len(scenarios)
    print(f"\npass rate: {passed}/{len(scenarios)} = {rate:.0%} (threshold {THRESHOLD:.0%})")
    sys.exit(0 if rate >= THRESHOLD else 1)


if __name__ == "__main__":
    main()
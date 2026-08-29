
import json
import sys
import time
from pathlib import Path

import yaml

from app.agent.agent_loop import run_agent
from app.agent.analytics_agent import run_analytics_agent
from app.config import settings
from evals.world import build_world

ROOT = Path(__file__).parent
OUT = ROOT / "out"
def load_scenarios()->list[dict]:
    return yaml.safe_load((ROOT / "scenarios.yaml").read_text(encoding="utf-8"))
def run_scenario(sc:dict) -> dict:
    world=build_world(sc["agent"],sc.get("world",{}))
    if sc["agent"]=="dispatch":
        events=run_agent(sc["question"],tools=world)
    else:
        events=run_analytics_agent(sc["question"],tools=world)

    t0=time.monotonic()
    transcript=[
        {"type": e.type, "step": e.step, "tool": e.tool_name, "payload": e.payload}
        for e in events
    ]
    final = next((e for e in transcript if e["type"] == "final"), None)
    return {
        "id": sc["id"],
        "agent": sc["agent"],
        "model": settings.agent_model,
        "question": sc["question"],
        "transcript": transcript,
        "answer": final["payload"]["answer"] if final else None,
        "seconds": round(time.monotonic() - t0, 2),
    }
def main(only: str|None=None, resume: bool=False, pace: float=0.0)->None:
    OUT.mkdir(exist_ok=True)
    for sc in load_scenarios():
        if only and sc["id"] != only:
            continue
        path = OUT / f"{sc['id']}.json"
        if resume and path.exists():
            # only skip scenarios that finished with an answer — errored ones
            # (429s, empty responses) get re-run and overwritten
            if json.loads(path.read_text(encoding="utf-8"))["answer"]:
                continue
        print(f"running {sc['id']} ...", flush=True)
        rec=run_scenario(sc)
        (OUT / f"{sc['id']}.json").write_text(
            json.dumps(rec, indent=2), encoding="utf-8"
        )
        status = "final" if rec["answer"] else "NO ANSWER"
        print(f"  {status} in {rec['seconds']}s, {len(rec['transcript'])} events")

        if pace:
            time.sleep(pace)   # stay under the free tier's requests/minute cap

if __name__ == "__main__":
    args = sys.argv[1:]
    pace = 0.0
    for a in list(args):
        if a.startswith("--pace="):
            pace = float(a.split("=", 1)[1])
            args.remove(a)
    resume = "--resume" in args
    args = [a for a in args if a != "--resume"]
    main(args[0] if args else None, resume=resume, pace=pace)


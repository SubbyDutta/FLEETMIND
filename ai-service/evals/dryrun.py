"""Zero-API-cost validation. Run BEFORE spending quota:  python -m evals.dryrun

1. Lints all scenarios (world shapes, tool names, expect keys).
2. Builds every scenario's canned world.
3. Drives the real agent loop end-to-end with a scripted fake Gemini on a1,
   then runs checks.py on the transcript. The fake deliberately skips
   search_runbooks, so checks MUST report failures — proving the graders work.
"""
import json

from google.genai import types

from app import tools as prod
from app.agent.agent_loop import run_agent
from evals.checks import check
from evals.runner import load_scenarios
from evals.world import build_world

KNOWN_EXPECT = {"must_call", "must_not_call", "runbook_before_action",
                "retrieved_doc_any", "action_args", "min_calls", "max_calls",
                "forbidden_args"}


def lint(sc: dict) -> list[str]:
    errs = []
    if sc.get("agent") not in ("dispatch", "analytics"):
        return [f"bad agent {sc.get('agent')!r}"]
    toolset = prod.DISPATCH_TOOLS if sc["agent"] == "dispatch" else prod.ANALYTICS_TOOLS
    for name, responses in (sc.get("world") or {}).items():
        if name not in toolset:
            errs.append(f"world tool {name!r} not in {sc['agent']} toolset")
        if not isinstance(responses, list) or not all(isinstance(r, dict) for r in responses):
            errs.append(f"world[{name!r}] must be a LIST of dicts")
    exp = sc.get("expect") or {}
    errs += [f"unknown expect key {k!r}" for k in exp if k not in KNOWN_EXPECT]
    for key in ("must_call", "must_not_call"):
        errs += [f"{key} names unknown tool {t!r}" for t in exp.get(key, [])
                 if t not in toolset]
    if not isinstance(sc.get("judge_rubric", ""), str):
        errs.append("judge_rubric must be a string")
    return errs


def _text_response(text: str) -> types.GenerateContentResponse:
    return types.GenerateContentResponse(candidates=[types.Candidate(
        content=types.Content(role="model", parts=[types.Part(text=text)]))])


def _call_response(name: str, args: dict) -> types.GenerateContentResponse:
    return types.GenerateContentResponse(candidates=[types.Candidate(
        content=types.Content(role="model", parts=[
            types.Part(function_call=types.FunctionCall(name=name, args=args))]))])


def make_fake_generate(script: list[types.GenerateContentResponse]):
    """A fake Gemini that plays back a fixed script of responses."""
    remaining = list(script)

    def fake(contents, system_prompt, tools=None):
        return remaining.pop(0)

    return fake


def main() -> None:
    scenarios = load_scenarios()

    bad = 0
    for sc in scenarios:
        errs = lint(sc)
        try:
            build_world(sc["agent"], sc.get("world", {}))
        except Exception as e:
            errs.append(f"build_world blew up: {type(e).__name__}: {e}")
        if errs:
            bad += 1
            print(f"LINT {sc['id']}: " + "; ".join(errs))
    print(f"lint+build: {len(scenarios) - bad}/{len(scenarios)} scenarios clean")

    # Deep plumbing test on a1 — fake brain, real loop, canned world.
    a1 = next(s for s in scenarios if s["id"] == "a1-stuck-two-windows-reassign")
    world = build_world(a1["agent"], a1["world"])
    fake = make_fake_generate([
        _call_response("get_order_status", {"order_id": "order-ab12cd34"}),
        _call_response("reassign_order", {
            "order_id": "order-ab12cd34", "new_driver_id": "driver-7",
            "reason": "stuck 2 windows (RB-STUCK)", "confirm": True}),
        _call_response("get_order_status", {"order_id": "order-ab12cd34"}),
        _text_response("Reassigned order-ab12cd34 to driver-7 per RB-STUCK."),
    ])
    events = list(run_agent(a1["question"], fake, tools=world))
    transcript = [{"type": e.type, "step": e.step, "tool": e.tool_name,
                   "payload": e.payload} for e in events]
    rec = {"id": a1["id"], "question": a1["question"], "transcript": transcript,
           "answer": transcript[-1]["payload"].get("answer")}
    json.dumps(rec)  # prove the transcript is JSON-serializable

    statuses = [e["payload"] for e in transcript
                if e["type"] == "tool_result" and e["tool"] == "get_order_status"]
    assert statuses[0]["assigned_driver"] == "driver-3", "1st canned response wrong"
    assert statuses[1]["assigned_driver"] == "driver-7", "pop-order broken"
    assert rec["answer"], "no final answer captured"

    fails = check(a1, rec)
    assert any("search_runbooks" in f for f in fails), \
        "checks.py failed to catch the deliberately skipped runbook step"
    print(f"a1 plumbing: {len(transcript)} events, world timeline OK, "
          f"checks caught {len(fails)} planted violations:")
    for f in fails:
        print(f"  - {f}")
    print("DRY RUN GREEN — safe to spend quota.")


if __name__ == "__main__":
    main()

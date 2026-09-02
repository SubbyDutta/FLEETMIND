ACTIONS = ("reassign_order", "notify_customer")


def check(sc: dict, rec: dict) -> list[str]:
    fails: list[str] = []
    exp = sc.get("expect", {})
    transcript = rec["transcript"]

    calls = [(e["tool"], e["payload"].get("args", {}))
             for e in transcript if e["type"] == "tool_call"]
    called = [name for name, _ in calls]

    if rec["answer"] is None:
        fails.append("no final answer (error event or step cap)")

    for t in exp.get("must_call", []):
        if t not in called:
            fails.append(f"never called {t}")
    for t in exp.get("must_not_call", []):
        if t in called:
            fails.append(f"called forbidden tool {t}")

    if exp.get("runbook_before_action"):
        for action in ACTIONS:
            if action in called and "search_runbooks" not in called[: called.index(action)]:
                fails.append(f"{action} fired without a prior search_runbooks")

    want = set(exp.get("retrieved_doc_any", []))
    if want:
        got = {
            c["doc_id"]
            for e in transcript
            if e["type"] == "tool_result" and e["tool"] == "search_runbooks"
            for c in e["payload"].get("chunks", [])
        }
        if not want & got:
            fails.append(f"retrieval missed {sorted(want)}; got {sorted(got)}")

    for tool, expected in exp.get("action_args", {}).items():
        actuals = [args for name, args in calls if name == tool]
        if not any(all(a.get(k) == v for k, v in expected.items()) for a in actuals):
            fails.append(f"{tool} never called with {expected}; saw {actuals}")

    for tool, n in exp.get("min_calls", {}).items():
        if called.count(tool) < n:
            fails.append(f"{tool} called {called.count(tool)}x, expected >= {n}")
    for tool, n in exp.get("max_calls", {}).items():
        if called.count(tool) > n:
            fails.append(f"{tool} called {called.count(tool)}x, expected <= {n}")

    for tool, banned in exp.get("forbidden_args", {}).items():
        for name, args in calls:
            if name == tool and all(args.get(k) == v for k, v in banned.items()):
                fails.append(f"{tool} called with forbidden args {banned}")

    forbidden_docs = set(exp.get("forbidden_docs", []))
    if forbidden_docs:
        got = {
            c["doc_id"]
            for e in transcript
            if e["type"] == "tool_result" and e["tool"] == "search_runbooks"
            for c in e["payload"].get("chunks", [])
        }
        leaked = forbidden_docs & got
        if leaked:
            fails.append(f"retrieval LEAKED forbidden docs {sorted(leaked)}")

    return fails
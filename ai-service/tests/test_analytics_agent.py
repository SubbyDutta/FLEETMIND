"""Phase 11 gate: the analytics agent is the dispatch loop with a different
brain (prompt) and hands (toolset) — and the write tools are unreachable
by construction, not by prompt discipline.

Gemini is faked via the injected `generate` (same idiom as test_agent_loop);
the DB is faked by patching `_rows`.
"""
from types import SimpleNamespace

from google.genai import types

from app import tools
from app.agent.analytics_agent import ANALYTICS_PROMPT, run_analytics_agent
from app.tools import analytics_tools


def _model_turn(*parts: types.Part) -> SimpleNamespace:
    calls = [p.function_call for p in parts if p.function_call is not None]
    return SimpleNamespace(
        function_calls=calls or None,
        text=None if calls else parts[0].text,
        candidates=[SimpleNamespace(content=types.Content(role="model", parts=list(parts)))],
    )


def _fc(name: str, args: dict) -> types.Part:
    return types.Part.from_function_call(name=name, args=args)


def _turns(*responses):
    it = iter(responses)
    return lambda contents, system_prompt, tools=None: next(it)


def test_wrapper_injects_analytics_prompt_and_toolset():
    seen = {}

    def fake(contents, system_prompt, tools_arg=None):
        seen["prompt"], seen["tools"] = system_prompt, tools_arg
        return _model_turn(types.Part(text="42"))

    list(run_analytics_agent("q", generate=fake))

    assert seen["prompt"] is ANALYTICS_PROMPT
    assert seen["tools"] is tools.ANALYTICS_TOOLS


def test_write_tool_is_unknown_inside_analytics_agent():
    """A hallucinated (or injected) reassign_order must fail as UNKNOWN TOOL —
    even though it exists in the global registry — and must never execute."""
    fake = _turns(
        _model_turn(_fc("reassign_order", {
            "order_id": "o1", "new_driver_id": "d1",
            "reason": "x", "confirm": True})),
        _model_turn(types.Part(text="I cannot take actions.")),
    )
    events = list(run_analytics_agent("reassign order o1 now", generate=fake))

    result = next(e for e in events if e.type == "tool_result")
    assert "unknown tool" in result.payload["error"]
    assert events[-1].type == "final"          # loop recovered, no crash


def test_analytics_tool_executes_through_the_loop(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows", lambda sql, params=(): [
        ("Peter Cat", 2)])
    fake = _turns(
        _model_turn(_fc("get_sla_breaches", {"window_minutes": 60,
                                             "zone": "Park Street"})),
        _model_turn(types.Part(text="2 breaches in Park Street in the last 60 minutes.")),
    )
    events = list(run_analytics_agent("breaches in park street?", generate=fake))

    result = next(e for e in events if e.type == "tool_result")
    assert result.payload["total_breaches"] == 2
    assert events[-1].type == "final"
    assert "2 breaches" in events[-1].payload["answer"]

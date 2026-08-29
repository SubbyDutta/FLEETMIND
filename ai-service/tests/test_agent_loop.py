"""Loop mechanics only — Gemini is faked via the injected `generate`,
tools are exercised through the real registry (validation) or patched
(_safe_invoke), so no network and no DB."""
from types import SimpleNamespace
from unittest.mock import patch

from google.genai import types

from app.agent.agent_loop import run_agent
from app.config import settings


def _model_turn(*parts: types.Part) -> SimpleNamespace:
    """Fake GenerateContentResponse: either function-call parts or one text part."""
    calls = [p.function_call for p in parts if p.function_call is not None]
    return SimpleNamespace(
        function_calls=calls or None,
        text=None if calls else parts[0].text,
        candidates=[SimpleNamespace(content=types.Content(role="model", parts=list(parts)))],
    )


def _fc(name: str, args: dict) -> types.Part:
    return types.Part.from_function_call(name=name, args=args)


def _turns(*responses):
    """generate() stand-in that replays canned model turns in order."""
    it = iter(responses)
    return lambda contents, system_prompt, tools=None: next(it)


def test_invalid_args_become_observation_not_crash():
    # limit=99 violates FindNearByDriverArgs (le=10) -> ValidationError -> observation
    fake = _turns(
        _model_turn(_fc("find_nearby_driver", {"order_id": "o1", "limit": 99})),
        _model_turn(types.Part(text="No idle drivers checked; invalid request corrected.")),
    )
    events = list(run_agent("q", generate=fake))

    results = [e for e in events if e.type == "tool_result"]
    assert results[0].payload["error"] == "invalid arguments"
    assert "details" in results[0].payload
    assert events[-1].type == "final"


def test_unknown_tool_is_reported_and_loop_continues():
    fake = _turns(
        _model_turn(_fc("launch_drone", {"order_id": "o1"})),
        _model_turn(types.Part(text="done")),
    )
    events = list(run_agent("q", generate=fake))

    results = [e for e in events if e.type == "tool_result"]
    assert "unknown tool" in results[0].payload["error"]
    assert events[-1].type == "final"


def test_step_cap_yields_error_not_infinite_loop():
    looping = _model_turn(_fc("get_order_status", {"order_id": "o1"}))
    fake = lambda contents, system_prompt, tools=None: looping

    with patch("app.agent.agent_loop._safe_invoke", return_value={"found": True}):
        events = list(run_agent("q", generate=fake))

    assert events[-1].type == "error"
    assert "step cap" in events[-1].payload["error"]
    assert len([e for e in events if e.type == "tool_call"]) == settings.agent_max_steps


def test_tool_results_are_fed_back_into_history():
    captured = []

    def fake(contents, system_prompt, tools=None):
        captured.append(len(contents))
        if len(captured) == 1:
            return _model_turn(_fc("launch_drone", {}))
        return _model_turn(types.Part(text="done"))

    list(run_agent("q", generate=fake))
    # turn 2 must see: question + model function-call turn + function-response turn
    assert captured == [1, 3]


def test_llm_failure_yields_error_event():
    def fake(contents, system_prompt, tools=None):
        raise RuntimeError("quota exhausted")

    events = list(run_agent("q", generate=fake))
    assert [e.type for e in events] == ["error"]
    assert "LLM call failed" in events[0].payload["error"]

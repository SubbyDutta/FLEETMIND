import json
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from typing import Any

from google.genai import types
from pydantic import ValidationError

from app.agent import gemini_client
from app.agent.prompts import DISPATCH_PROMPT
from app.config import settings
from app.tools.base import Toolset, run_tool

@dataclass(frozen=True)
class AgentEvent:
    type: str
    step:int
    tool_name:str=""
    payload:dict[str,Any]=field(default_factory=dict)

def _safe_invoke(name:str,raw_args:dict[str,Any],tools=None) -> dict[str,Any]:
    try:
        return run_tool(name,raw_args,tools)
    except KeyError:
        return {"error": f"unknown tool {name!r}"}
    except ValidationError as e:
        return {"error": "invalid arguments", "details": json.loads(e.json())}
    except Exception as e:  # tool bug / DB down — report, don't crash the stream
        return {"error": f"tool failed: {type(e).__name__}: {e}"}

def run_agent(
        question:str,
        generate:Callable[...,types.GenerateContentResponse]=gemini_client.generate,
        *,
        system_prompt:str=DISPATCH_PROMPT,
        tools: Toolset|None=None,
)->Iterator[AgentEvent]:
    contents=[types.Content(role="user",parts=[types.Part(text=question)])]
    for step in range(1,settings.agent_max_steps+1):
        try:
            resp=generate(contents,system_prompt,tools)
        except Exception as e:
            yield AgentEvent("error", step, payload={"error": f"LLM call failed: {e}"})
            return
        calls=resp.function_calls
        if not calls:
            if resp.text:
                yield AgentEvent("final", step, payload={"answer": resp.text})
            else:
                yield AgentEvent("error", step, payload={"error": "no answer"})
            return
        contents.append(resp.candidates[0].content)
        response_parts=[]
        for call in calls:
            args = dict(call.args or {})
            yield AgentEvent("tool_call", step, call.name, {"args": args})
            result = _safe_invoke(call.name, args,tools)
            yield AgentEvent("tool_result", step, call.name, result)
            response_parts.append(types.Part.from_function_response(
                name=call.name, response=result,
            ))
        contents.append(types.Content(role="user", parts=response_parts))

    yield AgentEvent("error", settings.agent_max_steps,
                     payload={"error": "step cap reached without a final answer"})

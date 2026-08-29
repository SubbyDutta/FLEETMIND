from typing import Any

from pydantic import BaseModel

from app import tools as prod
from app.tools.base import Toolset


class CannedTool:
    """Real tool's schema; scripted responses returned in order (last repeats)."""

    def __init__(self, real, responses: list[dict[str, Any]]):
        self.name = real.name
        self.description = real.description
        self.args_model = real.args_model
        self._responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def call(self, args: BaseModel) -> dict[str, Any]:
        self.calls.append(args.model_dump())
        if len(self._responses) > 1:
            return self._responses.pop(0)
        return self._responses[0]


def build_world(agent: str, script: dict[str, list[dict]]) -> Toolset:
    base = prod.DISPATCH_TOOLS if agent == "dispatch" else prod.ANALYTICS_TOOLS
    world: Toolset = {}
    for name, real in base.items():
        if name == "search_runbooks":
            world[name] = real  # local RAG is deterministic — eval it for real
        else:
            world[name] = CannedTool(
                real, script.get(name, [{"error": f"tool {name} not available in this scenario"}])
            )
    return world
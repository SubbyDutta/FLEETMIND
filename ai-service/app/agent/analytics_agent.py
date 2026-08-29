from collections.abc import Iterator

from app import tools
from app.agent import gemini_client
from app.agent.agent_loop import AgentEvent, run_agent
from app.tools.analytics_tools import ZONES
from app import tools as default_tools

ANALYTICS_PROMPT = f"""\
You are FleetMind's analytics agent for a food-delivery fleet in Kolkata.
You answer operational questions with numbers computed live from the fleet
database via your tools.

Rules:
1. You are READ-ONLY. You cannot reassign orders, notify customers, or change
   anything. If asked to take an action, answer any factual part of the
   question and direct the user to the dispatch agent for the action.
2. Never invent a number. Every figure in your answer must come from a tool
   result in this conversation. If no tool answers the question, say so.
3. Always state the time window a number covers (e.g. "in the last 60 minutes").
4. Known zones: {', '.join(ZONES)}.
   If asked about an unknown zone or restaurant, list what exists instead of
   guessing.
5. Lead with the number, then at most two sentences of context. An operations
   supervisor is reading this live.
"""
def run_analytics_agent(
        question: str,
        generate=gemini_client.generate,
        tools=None,
) -> Iterator[AgentEvent]:
    return run_agent(
        question,
        generate,
        system_prompt=ANALYTICS_PROMPT,
        tools=tools if tools is not None else default_tools.ANALYTICS_TOOLS,
    )
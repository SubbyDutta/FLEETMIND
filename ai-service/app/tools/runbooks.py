"""search_runbooks — read-only RAG lookup over the operational runbooks."""
from typing import Any

from pydantic import BaseModel, Field

from app.rag.retrieval import search
from app.tools.base import register

_MAX_CONTENT_CHARS = 600
class SearchRunbooksArgs(BaseModel):
    query:str=Field(
        description="Natural-language question about dispatch policy,e.g. "
                    "'when may an order be reassigned' or 'SLA credit percentges'.")
    k:int=Field(default=5,ge=1,le=10,description="How many chunks to return")

class SearchRunbooks:
    name="search_runbooks"
    description= (
        "Search the FleetMind operational runbooks (stuck-driver protocol, "
        "reassignment policy, SLA credit policy, escalation matrix, idle-driver "
        "policy). Use this BEFORE taking any action, to find the policy that "
        "authorizes it. Returns the most relevant policy excerpts with scores."
    )
    args_model=SearchRunbooksArgs

    def call(self,args:SearchRunbooksArgs)->dict[str,Any]:
        results=search(args.query,k=args.k)
        return {
            "chunks": [
                {
                    "doc_id": r.doc_id,
                    "heading": r.heading,
                    "content": r.content[:_MAX_CONTENT_CHARS],
                    "score": round(r.score, 4),
                }
                for r in results
            ]
        }

TOOL =register(SearchRunbooks())
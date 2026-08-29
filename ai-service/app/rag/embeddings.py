from __future__ import annotations

import hashlib
import json
from pathlib import Path
from functools import lru_cache

from google import genai
from google.genai import errors, types
from tenacity import retry, retry_if_exception, stop_after_attempt, wait_exponential

from app.config import settings

# Anchored to this file, not the CWD — pytest/scripts from any directory share one cache.
_CACHE_DIR = Path(__file__).resolve().parents[2] / ".cache" / "embeddings"
_BATCH_SIZE = 100

@lru_cache
def _client()->genai.Client:
    # Same broken-IPv6 workaround as agent/gemini_client.py — without it every
    # cold embed call (i.e. every runbook search) stalls ~170s on dead v6 routes.
    import httpx
    return genai.Client(
        api_key=settings.gemini_api_key,
        http_options=types.HttpOptions(
            client_args={"transport": httpx.HTTPTransport(local_address="0.0.0.0")},
            async_client_args={"transport": httpx.AsyncHTTPTransport(local_address="0.0.0.0")},
        ),
    )

# Same resilience policy as gemini_client.generate: quota (429) and transient
# server errors (5xx) get retried with backoff; anything else fails fast.
# Without this, one API blip fails the whole search_runbooks tool call.
def _is_retryable(e: BaseException) -> bool:
    return isinstance(e, errors.APIError) and (e.code == 429 or e.code >= 500)


@retry(
    retry=retry_if_exception(_is_retryable),
    wait=wait_exponential(min=1, max=8),
    stop=stop_after_attempt(5),
    reraise=True,
)
def _embed_batch(contents: list[str], task_type: str):
    return _client().models.embed_content(
        model=settings.embedding_model,
        contents=contents,
        config=types.EmbedContentConfig(
            task_type=task_type,
            output_dimensionality=settings.embedding_dims,
        ),
    )


def _normalize(vec: list[float])-> list[float]:
    #gemini embedding rreturns truncated vectors ,un-normalized
    #normalize so cosine similarity depds on vector direction
    norm = sum(x*x for x in vec)**0.5
    if norm ==0:
        raise ValueError("Embedding has zero Magnitutde")
    return [x/norm for x in vec]
def _cache_path(text: str,task_type:str)->Path:
    key= f"{settings.embedding_model}:{settings.embedding_dims}:{task_type}:{text}"
    digest= hashlib.sha256(key.encode()).hexdigest()
    return _CACHE_DIR / f"{digest}.json"

def _embed(texts: list[str],task_type:str)-> list[list[float]]:
    if not texts:
        return []
    _CACHE_DIR.mkdir(parents=True, exist_ok=True)
    result: list[list[float] | None] = [None] * len(texts)
    missing: list[tuple[int, str]] = []

    for i,text in enumerate(texts):
        path = _cache_path(text,task_type)

        if path.exists():
            result[i]=json.loads(path.read_text())
        else:
            missing.append((i,text))

    for start in range(0,len(missing),_BATCH_SIZE):
        batch = missing[start:start+_BATCH_SIZE]
        contents=[text for _,text in batch]
        response = _embed_batch(contents, task_type)
        if len(response.embeddings)!= len(batch):
            raise RuntimeError("embeddings mismatch")
        for(index,text),embedding in zip(batch,response.embeddings):
            vec = _normalize(embedding.values)
            # Boundary validation, not an internal invariant — a real exception,
            # never `assert` (stripped under python -O).
            if len(vec) != settings.embedding_dims:
                raise ValueError(
                    f"Expected {settings.embedding_dims}-dim embedding, got {len(vec)}"
                )
            result[index]=vec
            _cache_path(text,task_type).write_text(json.dumps(vec))

    # Fail loudly if any slot is unfilled — silently returning fewer vectors
    # than texts would misalign chunk<->embedding pairs downstream.
    if any(vec is None for vec in result):
        raise RuntimeError("Embedding pipeline left unfilled slots")
    return result
def embed_documents(texts: list[str]) -> list[list[float]]:
    return _embed(texts, "RETRIEVAL_DOCUMENT")


def embed_query(text: str) -> list[float]:
    if not text.strip():
        raise ValueError("Query cannot be empty")

    return _embed([text], "RETRIEVAL_QUERY")[0]
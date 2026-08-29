"""Splits Documents into token-bounded, self-describing chunks.

Strategy: split on '##' sections first (semantic boundaries embed and retrieve
far better than arbitrary windows). Only when a section exceeds the token
budget do we fall back recursively: paragraphs -> sentences -> words.
"""
from dataclasses import dataclass
from typing import Callable, Protocol
import re

from app.rag.loader import Document


_SPLITTERS: list[Callable[[str], list[str]]] = [
    lambda t: [p.strip() for p in t.split("\n\n") if p.strip()],                    # paragraphs
    lambda t: [s.strip() for s in re.split(r"(?<=[.!?])\s+", t) if s.strip()],      # sentences
    str.split,                                                                      # words
]
_JOINERS = ["\n\n", " ", " "]  # how units re-join at each level

_HEADING = re.compile(r"^##\s+(.+?)\s*$", re.MULTILINE)


class TokenCounter(Protocol):
    def __call__(self, text: str) -> int: ...


@dataclass(frozen=True)
class Chunk:
    doc_id: str
    chunk_no: int
    content: str
    heading: str
    token_count: int


class GeminiTokenCounter:


    def __init__(self, model: str | None = None):

        from google import genai
        from app.config import settings

        self._client = genai.Client(api_key=settings.gemini_api_key)
        self._model = model or settings.tokenizer_model
        self._cache: dict[str, int] = {}

    def __call__(self, text: str) -> int:
        if text not in self._cache:
            self._cache[text] = self._client.models.count_tokens(
                model=self._model, contents=text
            ).total_tokens
        return self._cache[text]


def chunk(
    doc: Document,
    counter: TokenCounter,
    max_tokens: int = 500,
    overlap: int = 80,
) -> list[Chunk]:

    if not 0 <= overlap < max_tokens:
        raise ValueError("need 0 <= overlap < max_tokens")

    chunks: list[Chunk] = []
    for heading, body in _sections(doc.text):
        if not body:
            continue
        header = f"Document: {doc.title}\nSection: {heading}\n\n"

        budget = max_tokens - counter(header)
        for piece in _pack(body, counter, budget, overlap, level=0):
            content = header + piece
            chunks.append(Chunk(doc.doc_id, len(chunks), content, heading, counter(content)))
    return chunks


def _sections(text: str) -> list[tuple[str, str]]:

    matches = list(_HEADING.finditer(text))
    sections=[]
    for i,m in enumerate(matches):
        heading=m.group(1)
        start=m.end()
        if i+1<len(matches):
            end=matches[i+1].start()
        else:
            end=len(text)
        body=text[start:end].strip()
        sections.append((heading,body))
    return sections


def _pack(text: str, counter: TokenCounter, budget: int, overlap: int, level: int) -> list[str]:

    if counter(text) <= budget:
        return [text]  

    units, joiner = _SPLITTERS[level](text), _JOINERS[level]
    pieces: list[str] = []
    cur: list[str] = []
    cur_tokens = 0

    for unit in units:
        n = counter(unit)
        if n > budget:  # single unit too large for a whole piece
            if cur:
                pieces.append(joiner.join(cur))
                cur, cur_tokens = [], 0
            if level + 1 < len(_SPLITTERS):
                pieces.extend(_pack(unit, counter, budget, overlap, level + 1))
            else:
                pieces.append(unit)  # one "word" over budget: accept it rather than loop forever
            continue

        if cur_tokens + n > budget:
            pieces.append(joiner.join(cur))

            cur = _tail(cur, counter, min(overlap, budget - n))
            cur_tokens = sum(counter(u) for u in cur)
        cur.append(unit)
        cur_tokens += n

    if cur:
        pieces.append(joiner.join(cur))
    return pieces


def _tail(units: list[str], counter: TokenCounter, overlap: int) -> list[str]:

    out: list[str] = []
    total = 0
    for u in reversed(units):
        total += counter(u)
        if total > overlap:
            break
        out.insert(0, u)
    return out


if __name__ == "__main__":  # smoke test: python -m app.rag.chunker (needs .env)
    from app.rag.loader import load_runbooks

    counter = GeminiTokenCounter()
    total = 0
    for doc in load_runbooks("app/rag/runbooks"):
        for c in chunk(doc, counter):
            print(f"{c.doc_id}/{c.chunk_no}  [{c.token_count} tok]  {c.heading}: "
                  f"{' '.join(c.content.split())[:60]}")
            total += 1
    print(f"\nTotal chunks: {total}")

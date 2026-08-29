"""Retrieval integration tests — the Phase 7 gate.

Need a running Postgres with indexed runbooks plus a Gemini key; the module
skips itself cleanly when either is missing, so `pytest` never errors on a
cold machine. Query embeddings hit the disk cache, so reruns cost no quota.
"""
import pytest

# Probe prerequisites once at collection time; any failure -> skip module.
try:
    from app.db import pool
    from app.rag.retrieval import search
    with pool.connection() as conn:
        _rows = conn.execute("SELECT count(*) FROM knowledge_chunks").fetchone()[0]
    _READY = _rows > 0
except Exception:
    _READY = False

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not _READY, reason="needs indexed Postgres + .env with Gemini key"),
]


@pytest.fixture(scope="session", autouse=True)
def close_pool_after_session():
    yield
    pool.close()  # avoid Python 3.14 thread-join noise at interpreter shutdown


# (query, expected doc, how deep it must appear)
# top_n=1 is the real gate; the reassignment query allows top-2 because the
# notification template legitimately near-ties it ("switched ... to a new rider").
CASES = [
    ("driver stuck in traffic",                 "stuck-driver",        1),
    ("vehicle not moving for a while",          "stuck-driver",        1),
    ("when do we give the customer money back", "sla-credit-policy",   1),
    ("SLA-CREDIT-20",                           "sla-credit-policy",   1),
    ("who do I call when things go wrong",      "escalation-matrix",   1),
    ("switch the order to another rider",       "reassignment-policy", 2),
]


@pytest.mark.parametrize("query,expected_doc,top_n", CASES)
def test_search_ranks_expected_doc(query, expected_doc, top_n):
    results = search(query)
    assert results, f"no results for {query!r}"
    top_docs = [r.doc_id for r in results[:top_n]]
    assert expected_doc in top_docs, (
        f"{query!r}: expected {expected_doc} in top {top_n}, got {top_docs}"
    )


def test_empty_query_returns_empty():
    assert search("   ") == []


def test_invalid_k_raises():
    with pytest.raises(ValueError):
        search("driver stuck", k=0)

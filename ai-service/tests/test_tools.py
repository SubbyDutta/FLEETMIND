import pytest
from pydantic import ValidationError

from app.tools.base import Tool, registry, run_tool
import app.tools.runbooks  # noqa: F401  (import = register)
import app.tools.geo       # noqa: F401
def test_registry_has_both_tools():
    assert "search_runbooks" in registry
    assert "find_nearby_driver" in registry


def test_registered_objects_satisfy_protocol():
    for tool in registry.values():
        assert isinstance(tool, Tool)
        assert tool.description  # empty description = broken agent in P9


def test_missing_order_id_raises_validation_error():
    with pytest.raises(ValidationError):
        run_tool("find_nearby_driver", {"limit": 3})


def test_unknown_tool_name_raises():
    with pytest.raises(KeyError):
        run_tool("launch_missiles", {})


# ---------- integration: needs live stack ----------

try:
    from app.db import pool
    with pool.connection() as conn:
        _chunks = conn.execute("SELECT count(*) FROM knowledge_chunks").fetchone()[0]
        _orders = conn.execute("SELECT count(*) FROM orders").fetchone()[0]
        _idle = conn.execute(
            "SELECT count(*) FROM drivers WHERE status = 'IDLE'"
        ).fetchone()[0]
    _READY = _chunks > 0 and _orders > 0
except Exception:
    _READY = False

needs_stack = pytest.mark.skipif(
    not _READY, reason="needs Postgres with indexed runbooks + simulator data"
)


@needs_stack
@pytest.mark.integration
def test_search_runbooks_finds_stuck_protocol():
    out = run_tool("search_runbooks", {"query": "driver stuck not moving"})
    assert out["chunks"], "no chunks returned"
    assert out["chunks"][0]["doc_id"] == "stuck-driver"


@needs_stack
@pytest.mark.integration
def test_find_nearby_driver_sorted_idle_and_limited():
    with pool.connection() as conn:
        order_id = conn.execute("SELECT id FROM orders LIMIT 1").fetchone()[0]

    out = run_tool("find_nearby_driver", {"order_id": order_id, "limit": 3})
    assert "error" not in out
    drivers = out["drivers"]
    assert len(drivers) <= 3
    if _idle == 0:
        assert drivers == []  # graceful empty, not an exception
    else:
        meters = [d["distance_meters"] for d in drivers]
        assert meters == sorted(meters), "not sorted nearest-first"
        assert all(m < 100_000 for m in meters), (
            "distances in the thousands of km smell like swapped lng/lat"
        )


@needs_stack
@pytest.mark.integration
def test_find_nearby_driver_unknown_order():
    out = run_tool("find_nearby_driver", {"order_id": "NOPE-999"})
    assert "error" in out and out["drivers"] == []
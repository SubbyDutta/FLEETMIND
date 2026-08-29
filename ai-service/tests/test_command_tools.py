"""Phase 8 Part 4 gate: gRPC write tools.

Unit tests (validation + confirm gate) run anywhere — no server, no RPC.
Integration tests need command-service's gRPC server on localhost:9091
plus orders in Postgres; the module skip-guards itself like test_retrieval.
"""
import pytest
from pydantic import ValidationError

from app.tools.base import registry, run_tool
import app.tools.command_tools  # noqa: F401  (import = register)


# ---------- unit: no server anywhere ----------

def test_registry_has_write_tools():
    for name in ("reassign_order", "notify_customer", "get_order_status"):
        assert name in registry


def test_reassign_missing_confirm_raises():
    with pytest.raises(ValidationError):
        run_tool("reassign_order", {
            "order_id": "O-1", "new_driver_id": "D-1", "reason": "x"})


def test_reassign_confirm_false_refuses_without_rpc():
    out = run_tool("reassign_order", {
        "order_id": "O-1", "new_driver_id": "D-1", "reason": "x",
        "confirm": False})
    assert out["success"] is False
    assert "confirm" in out["error"]


def test_notify_confirm_false_refuses_without_rpc():
    out = run_tool("notify_customer", {
        "order_id": "O-1", "message": "hi", "reason": "x", "confirm": False})
    assert out["success"] is False


# ---------- integration: needs Java gRPC server + data ----------

try:
    import grpc
    from app.config import settings
    from app.db import pool

    _chan = grpc.insecure_channel(settings.command_service_grpc)
    grpc.channel_ready_future(_chan).result(timeout=2)
    _chan.close()
    with pool.connection() as conn:
        _row = conn.execute(
            "SELECT id FROM orders WHERE status <> 'DELIVERED' LIMIT 1").fetchone()
    _ORDER_ID = _row[0] if _row else None
    _READY = _ORDER_ID is not None
except Exception:
    _READY = False
    _ORDER_ID = None

needs_server = pytest.mark.skipif(
    not _READY, reason="needs command-service gRPC on 9091 + orders in Postgres")


@needs_server
@pytest.mark.integration
def test_get_order_status_live_order():
    out = run_tool("get_order_status", {"order_id": _ORDER_ID})
    assert out["found"] is True
    assert out["order_id"] == _ORDER_ID
    assert out["status"]
    assert isinstance(out["open_alerts"], list)


@needs_server
@pytest.mark.integration
def test_get_order_status_unknown_order():
    out = run_tool("get_order_status", {"order_id": "NOPE-999"})
    assert out["found"] is False


@needs_server
@pytest.mark.integration
def test_reassign_unknown_order_fails_cleanly():
    out = run_tool("reassign_order", {
        "order_id": "NOPE-999", "new_driver_id": "D-1",
        "reason": "test", "confirm": True})
    assert out["success"] is False           # clean refusal, not an exception
    assert "message" in out or "error" in out

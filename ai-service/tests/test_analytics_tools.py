"""Phase 11 unit gate: analytics tools.

Pure unit tests — the DB is faked by patching `_rows`, so no Postgres needed.
The SQL itself is exercised by the live gate (NL answer vs hand-run query).
"""
import pytest
from pydantic import ValidationError

from app import tools
from app.tools import analytics_tools
from app.tools.base import registry, run_tool


# ---------- registration & toolset isolation ----------

def test_registry_has_analytics_tools():
    for name in ("get_sla_breaches", "get_eta_health", "get_driver_utilization"):
        assert name in registry


def test_analytics_toolset_has_no_write_tools():
    # Read-only by construction: the whole P11 security story in one assert.
    assert set(tools.ANALYTICS_TOOLS) == {
        "get_sla_breaches", "get_eta_health", "get_driver_utilization"}
    assert "reassign_order" not in tools.ANALYTICS_TOOLS
    assert "notify_customer" not in tools.ANALYTICS_TOOLS


def test_dispatch_toolset_has_no_analytics_tools():
    assert not set(tools.ANALYTICS_TOOLS) & set(tools.DISPATCH_TOOLS)
    assert "reassign_order" in tools.DISPATCH_TOOLS  # sanity: dispatch kept its hands


# ---------- get_sla_breaches ----------

def test_breaches_roll_up_restaurants_into_zones(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows", lambda sql, params=(): [
        ("Peter Cat", 3), ("Flurys", 1),          # both Park Street
        ("Arsalan", 2),                            # Park Circus
        ("Kathi Roll Hut", 1),                     # not in ZONE_OF -> Unknown
    ])
    out = run_tool("get_sla_breaches", {"window_minutes": 60})

    assert out["total_breaches"] == 7
    assert out["by_zone"]["Park Street"]["breaches"] == 4
    assert out["by_zone"]["Park Street"]["restaurants"] == {"Peter Cat": 3, "Flurys": 1}
    assert out["by_zone"]["Park Circus"]["breaches"] == 2
    assert out["by_zone"]["Unknown"]["breaches"] == 1


def test_breaches_zone_filter_is_case_insensitive(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows", lambda sql, params=(): [
        ("Peter Cat", 3), ("Arsalan", 2)])
    out = run_tool("get_sla_breaches", {"zone": "park street"})

    assert list(out["by_zone"]) == ["Park Street"]
    assert out["total_breaches"] == 3  # total respects the filter


def test_breaches_window_is_parameterized_not_interpolated(monkeypatch):
    seen = {}

    def spy(sql, params=()):
        seen["sql"], seen["params"] = sql, params
        return []

    monkeypatch.setattr(analytics_tools, "_rows", spy)
    out = run_tool("get_sla_breaches", {"window_minutes": 45})

    assert seen["params"] == (45,)
    assert "45" not in seen["sql"]          # value travels as a bind param only
    assert out["total_breaches"] == 0 and out["by_zone"] == {}


def test_breaches_rejects_out_of_range_window():
    with pytest.raises(ValidationError):
        run_tool("get_sla_breaches", {"window_minutes": 100_000})


# ---------- get_eta_health ----------

def test_eta_health_digests_the_single_aggregate_row(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows",
                        lambda sql, params=(): [(12, 84.649, 3)])
    out = run_tool("get_eta_health", {})

    assert out == {"active_orders": 12,
                   "avg_delay_seconds": 84.6,
                   "predicted_sla_breaches": 3}


def test_eta_health_survives_empty_fleet(monkeypatch):
    # count(*) = 0 -> avg() is NULL; must not crash on float(None)
    monkeypatch.setattr(analytics_tools, "_rows",
                        lambda sql, params=(): [(0, None, 0)])
    out = run_tool("get_eta_health", {})

    assert out["active_orders"] == 0
    assert out["avg_delay_seconds"] is None


# ---------- get_driver_utilization ----------

def test_utilization_counts_busy_vs_online(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows", lambda sql, params=(): [
        ("IDLE", 2), ("TO_PICKUP", 3), ("TO_DROP", 3), ("OFFLINE", 4)])
    out = run_tool("get_driver_utilization", {})

    assert out["busy"] == 6
    assert out["online"] == 8                  # OFFLINE excluded
    assert out["utilization_pct"] == 75.0


def test_utilization_all_offline_yields_none_not_zero_division(monkeypatch):
    monkeypatch.setattr(analytics_tools, "_rows",
                        lambda sql, params=(): [("OFFLINE", 5)])
    out = run_tool("get_driver_utilization", {})

    assert out["online"] == 0
    assert out["utilization_pct"] is None

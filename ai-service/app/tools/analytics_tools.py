from typing import Any

from pydantic import BaseModel, Field

from app.db import pool
from app.tenancy import require_tenant
from app.tools.base import register

# The DB has no zone column — restaurants are the grouping key, and the
# restaurant list is fixed in the simulator's OrderFactory. Zone knowledge
# lives here, in the tool layer.
ZONE_OF = {
    "Peter Cat": "Park Street", "Mocambo": "Park Street",
    "Flurys": "Park Street", "Trincas": "Park Street",
    "Kusum Rolls": "Park Street",
    "Aminia": "New Market", "Nizam's": "New Market",
    "Arsalan": "Park Circus", "Zeeshan": "Park Circus",
    "Oh! Calcutta": "Elgin Road",
    "6 Ballygunge Place": "Ballygunge", "Bhojohori Manna": "Ballygunge",
    "Kewpie's": "Bhawanipore", "Balwant Singh's": "Bhawanipore",
    "Golbari": "Shyambazar", "Mitra Cafe": "Shyambazar",
    "Banana Leaf": "Gariahat", "Tero Parbon": "Gariahat",
}
ZONES = sorted(set(ZONE_OF.values()))


def _rows(sql: str, params: tuple = ()) -> list[tuple]:
    with pool.connection() as conn:
        with conn.cursor() as cur:
            # Transaction-scoped, NOT conn.read_only (session-scoped): the pool
            # is shared with the RAG indexer, which writes. Must be the first
            # statement in the transaction.
            cur.execute("SET TRANSACTION READ ONLY")
            cur.execute(sql, params)
            return cur.fetchall()


class BreachArgs(BaseModel):
    window_minutes: int = Field(default=60, ge=1, le=1440,
                                description="Look-back window in minutes (default 60).")
    zone: str = Field(default="",
                      description=f"Optional zone filter, one of {ZONES}. Empty = all zones.")


class SlaBreaches:
    name = "get_sla_breaches"
    description = (
        "Count SLA_BREACH alerts raised in the last N minutes, grouped by city "
        "zone with a per-restaurant breakdown. Optionally filter to one zone. "
        f"Known zones: {', '.join(ZONES)}."
    )
    args_model = BreachArgs

    def call(self, args: BreachArgs) -> dict[str, Any]:
        rows = _rows("""
            SELECT o.restaurant, count(*)
            FROM alerts a
            JOIN orders o ON o.id = a.order_id
            WHERE a.tenant_id = %s
              AND o.tenant_id = %s
              AND a.type = 'SLA_BREACH'
              AND a.created_at >= now() - make_interval(mins => %s)
            GROUP BY o.restaurant
        """, (require_tenant(), require_tenant(), args.window_minutes))
        by_zone: dict[str, dict[str, Any]] = {}
        for restaurant, n in rows:
            z = by_zone.setdefault(ZONE_OF.get(restaurant, "Unknown"),
                                   {"breaches": 0, "restaurants": {}})
            z["breaches"] += n
            z["restaurants"][restaurant] = n
        if args.zone:
            wanted = args.zone.strip().lower()
            by_zone = {z: v for z, v in by_zone.items() if z.lower() == wanted}
        return {
            "window_minutes": args.window_minutes,
            "total_breaches": sum(z["breaches"] for z in by_zone.values()),
            "by_zone": by_zone,
        }


class EtaHealthArgs(BaseModel):
    pass  # snapshot over all active orders — no parameters


class EtaHealth:
    name = "get_eta_health"
    description = (
        "Fleet-wide ETA health for active (not delivered/cancelled) orders: how "
        "many are active, average delay vs promised ETA in seconds (negative = "
        "running early), and how many are currently predicted to miss their SLA."
    )
    args_model = EtaHealthArgs

    def call(self, args: EtaHealthArgs) -> dict[str, Any]:
        (active, avg_delay, predicted), = _rows("""
            SELECT count(*),
                   avg(extract(epoch FROM (current_eta - promised_eta))),
                   count(*) FILTER (WHERE current_eta > sla_deadline)
            FROM orders
            WHERE tenant_id = %s
              AND status NOT IN ('DELIVERED', 'CANCELLED')
              AND current_eta IS NOT NULL
        """, (require_tenant(),))
        return {
            "active_orders": active,
            "avg_delay_seconds": round(float(avg_delay), 1) if avg_delay is not None else None,
            "predicted_sla_breaches": predicted,
        }


class UtilizationArgs(BaseModel):
    pass


class DriverUtilization:
    name = "get_driver_utilization"
    description = (
        "Current fleet utilization: driver counts by status (IDLE / TO_PICKUP / "
        "TO_DROP / OFFLINE) and the busy percentage among online drivers."
    )
    args_model = UtilizationArgs

    def call(self, args: UtilizationArgs) -> dict[str, Any]:
        counts = {status: n for status, n in
                  _rows("SELECT status, count(*) FROM drivers WHERE tenant_id = %s GROUP BY status",
                        (require_tenant(),))}
        busy = counts.get("TO_PICKUP", 0) + counts.get("TO_DROP", 0)
        online = busy + counts.get("IDLE", 0)
        return {
            "by_status": counts,
            "online": online,
            "busy": busy,
            "utilization_pct": round(100 * busy / online, 1) if online else None,
        }


BREACHES = register(SlaBreaches())
ETA = register(EtaHealth())
UTILIZATION = register(DriverUtilization())
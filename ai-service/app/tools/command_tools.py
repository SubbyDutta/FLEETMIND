import math
from functools import lru_cache
from typing import Any

import grpc
from pydantic import BaseModel, Field

from app.config import settings
from app.proto_gen import telemetry_pb2_grpc, telemetry_pb2
from app.proto_gen import tools_pb2, tools_pb2_grpc
from app.tenancy import require_tenant
from app.tools.base import register


_DEADLINE_SECONDS = 3.0


def _tenant_metadata() -> tuple[tuple[str, str], ...]:
    return (("x-tenant-id", require_tenant()),)

_CONFIRM_FIELD = Field(
    description="Human-in-the-loop flag. Must be explicitly true to execute "
                "this state-changing action; set it only after policy checks."
)


@lru_cache
def _stub() -> "tools_pb2_grpc.ToolServiceStub":
    channel = grpc.insecure_channel(settings.command_service_grpc)
    return tools_pb2_grpc.ToolServiceStub(channel)
@lru_cache()
def _stub2()->"telemetry_pb2_grpc.TelemetryServiceStub":
    channel = grpc.insecure_channel(settings.command_service_grpc)
    return telemetry_pb2_grpc.TelemetryServiceStub(channel)



def _transport_error(e: grpc.RpcError) -> dict[str, Any]:
    code = e.code()
    if code in (grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED):
        return {"success": False,
                "error": f"command-service unreachable: {code.name}"}
    if code == grpc.StatusCode.NOT_FOUND:
        return {"success": False,
                "error": f"not found: {e.details() or 'no such id'} — check the id "
                         f"format (drivers look like 'driver-7', orders like 'order-1a2b3c4d')"}
    return {"success": False,
            "error": f"command-service error {code.name}: {e.details() or 'no details'}"}


class ReassignOrderArgs(BaseModel):
    order_id: str = Field(description="The order to reassign.")
    new_driver_id: str = Field(description="Replacement driver's FULL id, e.g. 'driver-7' "
                                           "— must currently be IDLE.")
    reason: str = Field(description="Runbook-backed justification, e.g. 'stuck 2 consecutive windows (RB-STUCK)'.")
    confirm: bool = _CONFIRM_FIELD


class ReassignOrder:
    name = "reassign_order"
    description = (
        "Reassign an order to a new driver. Only permitted when the reassignment "
        "policy allows it (stuck driver, unrecoverable SLA breach, breakdown, or "
        "supervisor instruction) — search the runbooks first and cite the rule in "
        "'reason'. Fails cleanly if the driver is no longer IDLE; on failure, find "
        "another candidate and retry."
    )
    args_model = ReassignOrderArgs

    def call(self, args: ReassignOrderArgs) -> dict[str, Any]:
        if not args.confirm:
            return {"success": False,
                    "error": "confirm=true required to execute a reassignment"}
        try:
            resp = _stub().ReassignOrder(
                tools_pb2.ReassignRequest(
                    order_id=args.order_id,
                    new_driver_id=args.new_driver_id,
                    reason=args.reason,
                ),
                timeout=_DEADLINE_SECONDS,
                metadata=_tenant_metadata(),
            )
        except grpc.RpcError as e:
            return _transport_error(e)
        return {"success": resp.success, "message": resp.message}

def _haversine_meters(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


class TelemetryArgs(BaseModel):
    driver_id: str = Field(description="The driver's FULL id, e.g. 'driver-7'. "
                                       "A bare number like '7' will not match any driver.")
    samples: int = Field(default=5, ge=2, le=15,
                         description="How many 1-second GPS samples to observe (2-15).")



class Telemetry:
    name = "watch_driver"
    description = (
        "Watch a driver's live GPS telemetry for a few seconds and report whether "
        "they are actually moving right now: distance covered, average speed, and "
        "a stationary/moving verdict. Use this to VERIFY a stuck-driver alert with "
        "fresh evidence before reassigning, or to confirm a driver resumed moving "
        "after an action. Read-only; takes ~`samples` seconds to run."
    )
    args_model = TelemetryArgs

    def call(self, args: TelemetryArgs) -> dict[str, Any]:
        request = telemetry_pb2.WatchRequest(
            driver_id=args.driver_id,
            samples=args.samples,
        )
        pings = []
        try:
            # The stream itself takes ~1s per sample — the deadline must outlive
            # it, so it scales with `samples` instead of the flat unary deadline.
            for ping in _stub2().WatchDriver(request, timeout=args.samples * 1.0 + 3.0,
                                             metadata=_tenant_metadata()):
                pings.append({
                    "lat": ping.lat,
                    "lng": ping.lng,
                    "status": ping.status,
                    "speed_kmph": ping.speed_kmph,
                    "ts_millis": ping.ts_millis,
                })
        except grpc.RpcError as e:
            return _transport_error(e)
        if not pings:
            return {"error": f"no telemetry received for driver {args.driver_id!r}"}


        first, last = pings[0], pings[-1]
        moved = _haversine_meters(first["lat"], first["lng"], last["lat"], last["lng"])
        return {
            "driver_id": args.driver_id,
            "samples": len(pings),
            "seconds_observed": round((last["ts_millis"] - first["ts_millis"]) / 1000, 1),
            "moved_meters": round(moved, 1),
            "avg_speed_kmph": round(sum(p["speed_kmph"] for p in pings) / len(pings), 1),
            "verdict": "stationary" if moved < 15 else "moving",
            "status_seen": last["status"],
        }

class NotifyCustomerArgs(BaseModel):
    order_id: str = Field(description="The order whose customer to notify.")
    message: str = Field(description="Customer-facing text. Follow the notification templates in the runbooks.")
    reason: str = Field(description="Why the notification is warranted, e.g. 'SLA breach mitigation'.")
    confirm: bool = _CONFIRM_FIELD


class NotifyCustomer:
    name = "notify_customer"
    description = (
        "Queue a notification to the customer of an order (delay updates, driver "
        "changes, credit offers). Use the wording templates from the runbooks. "
        "Does not change order state."
    )
    args_model = NotifyCustomerArgs

    def call(self, args: NotifyCustomerArgs) -> dict[str, Any]:
        if not args.confirm:
            return {"success": False,
                    "error": "confirm=true required to send a customer notification"}
        try:
            resp = _stub().NotifyCustomer(
                tools_pb2.NotifyRequest(
                    order_id=args.order_id,
                    message=args.message,
                    reason=args.reason,
                ),
                timeout=_DEADLINE_SECONDS,
                metadata=_tenant_metadata(),
            )
        except grpc.RpcError as e:
            return _transport_error(e)
        return {"success": resp.success, "message": resp.message}


class GetOrderStatusArgs(BaseModel):
    order_id: str = Field(description="The order to look up.")


class GetOrderStatus:
    name = "get_order_status"
    description = (
        "Get the live status of an order: current state, assigned driver, SLA "
        "deadline, promised and current ETA, and any open alerts. Use this first "
        "when investigating an incident, and again after acting to verify effect."
    )
    args_model = GetOrderStatusArgs

    def call(self, args: GetOrderStatusArgs) -> dict[str, Any]:
        try:
            resp = _stub().GetOrderStatus(
                tools_pb2.OrderStatusRequest(order_id=args.order_id),
                timeout=_DEADLINE_SECONDS,
                metadata=_tenant_metadata(),
            )
        except grpc.RpcError as e:
            return _transport_error(e)
        if not resp.found:
            return {"found": False, "error": f"order {args.order_id!r} not found"}
        return {
            "found": True,
            "order_id": resp.order_id,
            "status": resp.status,
            "customer_name": resp.customer_name,
            "restaurant": resp.restaurant,
            "assigned_driver": resp.assigned_driver or None,
            "sla_deadline": resp.sla_deadline,
            "promised_eta": resp.promised_eta or None,
            "current_eta": resp.current_eta or None,
            "open_alerts": [
                {"type": a.type, "severity": a.severity,
                 "reason": a.reason, "created_at": a.created_at}
                for a in resp.open_alerts
            ],
        }

class DriverOverviewArgs(BaseModel):
    driver_id: str = Field(description="The driver's FULL id, e.g. 'driver-7'. "
                                       "A bare number like '7' will not match any driver.")


class GetDriverOverview:
    name = "get_driver_overview"
    description = (
        "Look up a driver: live status, speed, when they were last seen, their "
        "CURRENT ORDER (including its order_id), and any open alerts on them. "
        "Use this FIRST when investigating a driver — it gives you the real "
        "order_id to investigate further, so you never have to guess one."
    )
    args_model = DriverOverviewArgs

    def call(self, args: DriverOverviewArgs) -> dict[str, Any]:
        try:
            resp = _stub().GetDriverOverview(
                tools_pb2.DriverOverviewRequest(driver_id=args.driver_id),
                timeout=_DEADLINE_SECONDS,
                metadata=_tenant_metadata(),
            )
        except grpc.RpcError as e:
            return _transport_error(e)
        if not resp.found:
            return {"found": False,
                    "error": f"driver {args.driver_id!r} not found — ids look like 'driver-7'"}
        out = {
            "found": True,
            "driver_id": resp.driver_id,
            "name": resp.name,
            "status": resp.status,
            "speed_kmph": resp.speed_kmph,
            "last_seen": resp.last_seen,
            "open_alerts": [
                {"type": a.type, "severity": a.severity,
                 "reason": a.reason, "created_at": a.created_at}
                for a in resp.open_alerts
            ],
        }
        # Message-field presence: this is exactly why CurrentOrder is a nested
        # message — "no active order" is a fact, not an empty string.
        if resp.HasField("current_order"):
            co = resp.current_order
            out["current_order"] = {
                "order_id": co.order_id, "status": co.status,
                "restaurant": co.restaurant, "current_eta": co.current_eta,
                "sla_deadline": co.sla_deadline,
            }
        else:
            out["current_order"] = None
        return out

REASSIGN = register(ReassignOrder())
NOTIFY = register(NotifyCustomer())
STATUS = register(GetOrderStatus())
WATCHDRIVER=register(Telemetry())
DRIVER_OVERVIEW = register(GetDriverOverview())

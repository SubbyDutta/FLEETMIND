from typing import Any

from pydantic import BaseModel, Field

from app.db import pool
from app.tenancy import require_tenant
from app.tools.base import register

_PICKUP_SQL="""
SELECT ST_X(pickup::geometry) AS lng, ST_Y(pickup::geometry) AS lat
FROM orders
WHERE tenant_id=%(tenant)s AND id=%(order_id)s;
"""
_KNN_SQL="""
SELECT id,name,
ST_Distance(location,ST_MakePoint(%(lng)s,%(lat)s)::geography) AS meters
FROM drivers
WHERE tenant_id=%(tenant)s AND status ='IDLE'
ORDER BY location <-> ST_MakePoint(%(lng)s, %(lat)s)::geography
    LIMIT %(limit)s;
"""

class FindNearByDriverArgs(BaseModel):
    order_id:str=Field(description="The order whose pickup point to search around.")
    limit: int = Field(default=3, ge=1, le=10,
                       description="Maximum number of candidate drivers to return.")
class FindNearByDriver:
    name="find_nearby_driver"
    description=(
        "Find the closest IDLE drivers to an order's pickup location, sorted "
        "nearest first with distance in meters. Use this to pick a replacement "
        "driver before reassigning an order. Returns an empty list when no "
        "drivers are idle — consult the runbooks for what to do in that case."
    )
    args_model=FindNearByDriverArgs
    def call(self,args:FindNearByDriverArgs)->dict[str,Any]:
        tenant = require_tenant()
        with pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute(_PICKUP_SQL, {"order_id": args.order_id, "tenant": tenant})
                row=cur.fetchone()
                if row is None:
                    return{"error": f"order{args.order_id} not found","drivers":[]}
                lng,lat=row
                cur.execute(_KNN_SQL, {"lng": lng, "lat": lat, "limit": args.limit, "tenant": tenant})
                drivers = cur.fetchall()

        return {
            "pickup": {"lng": lng, "lat": lat},
            "drivers": [
                {"driver_id": d[0], "name": d[1], "distance_meters": round(float(d[2]), 1)}
                for d in drivers
            ],
        }

TOOL = register(FindNearByDriver())
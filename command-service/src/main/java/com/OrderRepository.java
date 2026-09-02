package com;

import fleetmind.events.EtaUpdate;
import fleetmind.events.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public void upsert(OrderEvent order) {
        jdbc.update(
                """
                INSERT INTO orders
                    (id, customer_name, restaurant, pickup, dropoff,
                     status, assigned_driver, sla_deadline, created_at, updated_at)
                VALUES (?, ?, ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    status          = EXCLUDED.status,
                    assigned_driver = EXCLUDED.assigned_driver,
                    updated_at      = now()
                """,
                order.getOrderId(),
                order.getCustomerName(),
                order.getRestaurantName(),
                order.getPickupLng(), order.getPickupLat(),    // lng, lat
                order.getDropoffLng(), order.getDropoffLat(),  // lng, lat
                order.getStatus().name(),
                order.getAssignedDriverId(),
                Timestamp.from(order.getSlaDeadLineTs()),
                Timestamp.from(order.getCreatedTs())
        );
    }

    public void markDelivered(String orderId) {
        jdbc.update(
                "UPDATE orders SET status = 'DELIVERED', updated_at = now() WHERE id = ?",
                orderId
        );
        jdbc.update("UPDATE alerts SET resolved=true WHERE order_id=? AND resolved=false", orderId);
    }

    public void updateCurrentEta(EtaUpdate eta)
    {
        Instant predictedArrival=
                eta.getComputedTs().plusSeconds((long)(eta.getEtaMinutes()*60));
        jdbc.update("UPDATE orders SET current_eta = ?,updated_at = now() WHERE id = ?",
                Timestamp.from(predictedArrival),
                eta.getOrderId()
                );
    }
    @Cacheable(cacheNames = "orders", key = "T(com.TenantContext).require() + ':active'")
    public List<Map<String,Object>> findActive()
    {
        return jdbc.queryForList(
                """
               SELECT id, customer_name, restaurant, status, assigned_driver,
                      ST_Y(pickup::geometry) AS pickup_lat, ST_X(pickup::geometry) AS pickup_lng,
                      ST_Y(dropoff::geometry) AS dropoff_lat, ST_X(dropoff::geometry) AS dropoff_lng,
                      sla_deadline,current_eta,updated_at
               FROM orders
               WHERE tenant_id = ? AND status <> 'DELIVERED'
               ORDER BY updated_at DESC

""",
                TenantContext.require()
        );
    }
    public Map<String, Object> findActiveByDriver(String driverId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, status,
                       ST_Y(pickup::geometry)  AS pickup_lat,  ST_X(pickup::geometry)  AS pickup_lng,
                       ST_Y(dropoff::geometry) AS dropoff_lat, ST_X(dropoff::geometry) AS dropoff_lng
                FROM orders
                WHERE tenant_id = ?
                  AND assigned_driver = ?
                  AND status <> 'DELIVERED'
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                TenantContext.require(),
                driverId
        );
        if (rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }


}

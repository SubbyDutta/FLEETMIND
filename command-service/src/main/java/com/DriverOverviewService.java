package com;

import com.fleetmind.tools.Alert;
import com.fleetmind.tools.CurrentOrder;
import com.fleetmind.tools.DriverOverviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DriverOverviewService {
    private final JdbcTemplate jdbc;

    public DriverOverviewResponse getOverview(String driverId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, status, speed_kmph, updated_at
                FROM drivers WHERE tenant_id = ? AND id = ?
                """, TenantContext.require(), driverId);
        if (rows.isEmpty()) {
            return DriverOverviewResponse.newBuilder().setFound(false).build();
        }
        Map<String, Object> d = rows.getFirst();
        DriverOverviewResponse.Builder resp = DriverOverviewResponse.newBuilder()
                .setFound(true)
                .setDriverId((String) d.get("id"))
                .setName((String) d.get("name"))
                .setStatus((String) d.get("status"))
                .setSpeedKmph(d.get("speed_kmph") == null ? 0.0 : (Double) d.get("speed_kmph"))
                .setLastSeen(iso(d.get("updated_at")));


        jdbc.queryForList("""
                SELECT id, status, restaurant, current_eta, sla_deadline
                FROM orders
                WHERE tenant_id = ? AND assigned_driver = ? AND status NOT IN ('DELIVERED', 'CANCELLED')
                ORDER BY updated_at DESC
                LIMIT 1
                """, TenantContext.require(), driverId)
                .forEach(o -> resp.setCurrentOrder(CurrentOrder.newBuilder()
                        .setOrderId((String) o.get("id"))
                        .setStatus((String) o.get("status"))
                        .setRestaurant((String) o.get("restaurant"))
                        .setCurrentEta(iso(o.get("current_eta")))
                        .setSlaDeadline(iso(o.get("sla_deadline")))
                        .build()));

        jdbc.queryForList("""
                SELECT type, severity, reason, created_at
                FROM alerts
                WHERE tenant_id = ? AND driver_id = ? AND resolved = false
                ORDER BY created_at DESC
                """, TenantContext.require(), driverId)
                .forEach(a -> resp.addOpenAlerts(Alert.newBuilder()
                        .setType((String) a.get("type"))
                        .setSeverity((String) a.get("severity"))
                        .setReason((String) a.get("reason"))
                        .setCreatedAt(iso(a.get("created_at")))
                        .build()));
        return resp.build();
    }

    private static String iso(Object ts) {
        return ts == null ? "" : ((Timestamp) ts).toInstant().toString();
    }
}
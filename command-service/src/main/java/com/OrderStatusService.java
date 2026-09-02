package com;

import com.fleetmind.tools.Alert;
import com.fleetmind.tools.OrderStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderStatusService {
    private final JdbcTemplate jdbc;
    public OrderStatusResponse getStatus(String orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, customer_name, restaurant, assigned_driver,
                       sla_deadline, promised_eta, current_eta
                FROM orders WHERE tenant_id = ? AND id = ?
                """, TenantContext.require(), orderId);
        if (rows.isEmpty()) {
            return OrderStatusResponse.newBuilder().setFound(false).build();
        }
        Map<String, Object> o = rows.getFirst();
        OrderStatusResponse.Builder resp = OrderStatusResponse.newBuilder()
                .setFound(true)
                .setOrderId((String) o.get("id"))
                .setStatus((String) o.get("status"))
                .setCustomerName((String) o.get("customer_name"))
                .setRestaurant((String) o.get("restaurant"))
                .setAssignedDriver(nullToEmpty((String) o.get("assigned_driver")))
                .setSlaDeadline(iso(o.get("sla_deadline")))
                .setPromisedEta(iso(o.get("promised_eta")))
                .setCurrentEta(iso(o.get("current_eta")));
        jdbc.queryForList("""
                SELECT type, severity, reason, created_at
                FROM alerts
                WHERE tenant_id = ? AND order_id = ? AND resolved = false
                ORDER BY created_at DESC
                """, TenantContext.require(), orderId
        ).forEach(a -> resp.addOpenAlerts(Alert.newBuilder()
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}

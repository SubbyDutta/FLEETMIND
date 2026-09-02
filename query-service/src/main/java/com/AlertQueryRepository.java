package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class AlertQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String COLS =
            "id, type, severity, order_id, driver_id, reason, resolved, created_at";

    private static final RowMapper<Alert> MAPPER = (rs, i) -> new Alert(
            rs.getLong("id"),
            rs.getString("type"),
            rs.getString("severity"),
            rs.getString("order_id"),
            rs.getString("driver_id"),
            rs.getString("reason"),
            rs.getBoolean("resolved"),
            OrderQueryRepository.iso(rs, "created_at")
    );

    public List<Alert> findOpen(int limit) {
        return jdbc.query(
                "SELECT " + COLS + """
                 FROM alerts
                 WHERE tenant_id = :tenant AND resolved = false
                 ORDER BY created_at DESC
                 LIMIT :limit
                """,
                Map.of("limit", limit, "tenant", CurrentTenant.require()), MAPPER);
    }

    public List<Alert> findOpenByOrderIds(Collection<String> orderIds) {
        return jdbc.query(
                "SELECT " + COLS + " FROM alerts WHERE tenant_id = :tenant AND resolved = false AND order_id IN (:ids)",
                Map.of("ids", orderIds, "tenant", CurrentTenant.require()), MAPPER);
    }
}

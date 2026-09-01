package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class OrderQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String COLS = """
            id, customer_name, restaurant, status, assigned_driver,
            ST_Y(pickup::geometry)  AS pickup_lat,  ST_X(pickup::geometry)  AS pickup_lng,
            ST_Y(dropoff::geometry) AS dropoff_lat, ST_X(dropoff::geometry) AS dropoff_lng,
            sla_deadline, current_eta, created_at
            """;

    private static final RowMapper<Order> MAPPER = (rs, i) -> new Order(
            rs.getString("id"),
            rs.getString("customer_name"),
            rs.getString("restaurant"),
            rs.getString("status"),
            new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
            new GeoPoint(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lng")),
            rs.getString("assigned_driver"),
            iso(rs, "sla_deadline"),
            iso(rs, "current_eta"),
            iso(rs, "created_at")
    );

    static String iso(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant().toString();
    }

    public List<Order> findActive(int limit) {
        return jdbc.query(
                "SELECT " + COLS + """
                FROM orders
                WHERE status NOT IN ('DELIVERED','CANCELLED')
                ORDER BY updated_at DESC
                LIMIT :limit
                """,
                Map.of("limit", limit), MAPPER);
    }

    public Order findById(String id) {
        List<Order> rows = jdbc.query(
                "SELECT " + COLS + " FROM orders WHERE id = :id",
                Map.of("id", id), MAPPER);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Order> findActiveByDriverIds(Collection<String> driverIds) {
        return jdbc.query(
                "SELECT " + COLS + """
                FROM orders
                WHERE status NOT IN ('DELIVERED','CANCELLED')
                  AND assigned_driver IN (:ids)
                """,
                Map.of("ids", driverIds), MAPPER);
    }
}

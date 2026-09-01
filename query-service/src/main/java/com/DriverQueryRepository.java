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
public class DriverQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String COLS = """
            id, name, status,
            ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng,
            speed_kmph
            """;

    private static final RowMapper<Driver> MAPPER = (rs, i) -> new Driver(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("status"),
            new GeoPoint(rs.getDouble("lat"), rs.getDouble("lng")),
            (Double) rs.getObject("speed_kmph")
    );

    public List<Driver> findAll(String status) {
        if (status == null) {
            return jdbc.query("SELECT " + COLS + " FROM drivers ORDER BY id", Map.of(), MAPPER);
        }
        return jdbc.query(
                "SELECT " + COLS + " FROM drivers WHERE status = :status ORDER BY id",
                Map.of("status", status), MAPPER);
    }

    public Driver findById(String id) {
        List<Driver> rows = jdbc.query(
                "SELECT " + COLS + " FROM drivers WHERE id = :id",
                Map.of("id", id), MAPPER);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Driver> findByIds(Collection<String> ids) {
        return jdbc.query(
                "SELECT " + COLS + " FROM drivers WHERE id IN (:ids)",
                Map.of("ids", ids), MAPPER);
    }
}

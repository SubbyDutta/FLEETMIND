package com;


import fleetmind.events.GpsPing;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DriverRepository {

    private final JdbcTemplate jdbc;

    public void upsertPosition(GpsPing ping) {
        jdbc.update(
                """
                INSERT INTO drivers (id, name, status, location, speed_kmph, updated_at)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    status     = EXCLUDED.status,
                    location   = EXCLUDED.location,
                    speed_kmph = EXCLUDED.speed_kmph,
                    updated_at = now()
                """,
                ping.getDriverId(),
                ping.getDriverId(),
                ping.getStatus().name(),
                ping.getLng(), ping.getLat(),  // ST_MakePoint(x = lng, y = lat) — order matters!!!!!!
                ping.getSpeedKmph()
        );
    }
    @Cacheable(cacheNames = "drivers", key = "'all'")
    public List<Map<String,Object>> findAll()
    {
        return jdbc.queryForList(
                """
                    SELECT id, name, status,
                           ST_Y(location::geometry) AS lat,
                           ST_X(location::geometry) AS lng,
                           speed_kmph, updated_at
                    FROM drivers
                    ORDER BY id
                    """
        );
    }
    public Map<String,Object> findById(String driverId)
    {
        List<Map<String,Object>> rows=jdbc.queryForList(
                """
                  SELECT id, status,
                         ST_Y(location::geometry) AS lat,
                         ST_X(location::geometry) AS lng
                  FROM drivers
                  WHERE id=?
            """,driverId
        );
        if(rows.isEmpty())
        {
            return null;
        }
        return rows.getFirst();
    }
}
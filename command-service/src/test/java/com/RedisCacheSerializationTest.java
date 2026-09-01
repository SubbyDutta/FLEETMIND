package com;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P15 pin: the dashboard repos return List<Map<String,Object>> whose values are
 * whatever the JDBC driver produced — String, Double, java.sql.Timestamp, null.
 * If any of those types doesn't survive the JSON round-trip through Redis, the
 * API response shape silently changes on cache HITS only (the nastiest kind of
 * bug: works on the first request, wrong on the second). This test feeds the
 * serializer a realistic row and pins that every value comes back as the same
 * type it went in as.
 */
class RedisCacheSerializationTest {

    private final GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();

    @Test
    @SuppressWarnings("unchecked")
    void rowShapedListSurvivesTheRoundTrip() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "driver-7");
        row.put("name", "driver-7");
        row.put("status", "TO_DROP");
        row.put("lat", 22.5726);
        row.put("lng", 88.3639);
        row.put("speed_kmph", null);
        row.put("updated_at", Timestamp.from(Instant.parse("2026-08-31T10:15:30.123Z")));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);

        byte[] bytes = json.serialize(rows);
        Object back = json.deserialize(bytes);

        assertThat(back).isInstanceOf(List.class);
        List<Map<String, Object>> restoredRows = (List<Map<String, Object>>) back;
        assertThat(restoredRows).hasSize(1);

        Map<String, Object> restored = restoredRows.getFirst();
        assertThat(restored.get("id")).isEqualTo("driver-7");
        assertThat(restored.get("status")).isEqualTo("TO_DROP");
        assertThat(restored.get("lat")).isEqualTo(22.5726);
        assertThat(restored).containsKey("speed_kmph");
        assertThat(restored.get("speed_kmph")).isNull();
        assertThat(restored.get("updated_at"))
                .isInstanceOf(Timestamp.class)
                .isEqualTo(Timestamp.from(Instant.parse("2026-08-31T10:15:30.123Z")));
    }
}

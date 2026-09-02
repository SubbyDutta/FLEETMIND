package com;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Needs the docker-compose Postgres up (real PostGIS — no H2 substitute).
 * @JdbcTest wraps each test in a rolled-back transaction, so fixtures never
 * leak into the live tables the simulator is writing to.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReassignService.class, OutboxRepository.class})
class ReassignServiceTest {

    @Autowired ReassignService service;
    @Autowired JdbcTemplate jdbc;

    private static final String ORDER = "T-ORD-1";
    private static final String OLD_DRIVER = "T-DRV-OLD";
    private static final String NEW_DRIVER = "T-DRV-NEW";

    @BeforeEach
    void fixtures() {
        TenantContext.set("acme");
        insertDriver(OLD_DRIVER, "TO_PICKUP");
        insertDriver(NEW_DRIVER, "IDLE");
        insertOrder(ORDER, "ASSIGNED", OLD_DRIVER);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void happyPath_updatesOrderAndWritesOutbox_thePairIsThePattern() {
        service.reassign(ORDER, NEW_DRIVER, "stuck 2 windows");

        assertEquals(NEW_DRIVER, jdbc.queryForObject(
                "SELECT assigned_driver FROM orders WHERE id = ?", String.class, ORDER));

        assertEquals("TO_PICKUP", jdbc.queryForObject(
                "SELECT status FROM drivers WHERE id = ?", String.class, NEW_DRIVER));

        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM outbox
                WHERE aggregate_id = ? AND event_type = 'DispatchAction'
                  AND payload->>'action' = 'REASSIGN' AND payload->>'targetId' = ?
                """, Integer.class, ORDER, NEW_DRIVER));
    }

    @Test
    void busyDriver_rejected_nothingChanges() {
        jdbc.update("UPDATE drivers SET status = 'TO_DROP' WHERE id = ?", NEW_DRIVER);

        assertThrows(ToolRejection.class,
                () -> service.reassign(ORDER, NEW_DRIVER, "x"));

        assertEquals(OLD_DRIVER, jdbc.queryForObject(
                "SELECT assigned_driver FROM orders WHERE id = ?", String.class, ORDER));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, ORDER));
    }

    @Test
    void unknownDriver_rejected_sameAsBusy() {
        assertThrows(ToolRejection.class,
                () -> service.reassign(ORDER, "T-DRV-GHOST", "x"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, ORDER));
    }

    @Test
    void unknownOrder_rejected() {
        assertThrows(ToolRejection.class,
                () -> service.reassign("NOPE-999", NEW_DRIVER, "x"));
    }

    @Test
    void sameDriver_rejected() {
        assertThrows(ToolRejection.class,
                () -> service.reassign(ORDER, OLD_DRIVER, "x"));
    }

    @Test
    void deliveredOrder_rejected() {
        jdbc.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", ORDER);
        assertThrows(ToolRejection.class,
                () -> service.reassign(ORDER, NEW_DRIVER, "x"));
    }

    @Test
    void crossTenant_orderIsInvisible_notForbidden() {
        TenantContext.set("globex");
        ToolRejection rejection = assertThrows(ToolRejection.class,
                () -> service.reassign(ORDER, NEW_DRIVER, "x"));
        assertEquals(true, rejection.getMessage().contains("NOT FOUND"));
        TenantContext.set("acme");
        assertEquals(OLD_DRIVER, jdbc.queryForObject(
                "SELECT assigned_driver FROM orders WHERE id = ?", String.class, ORDER));
    }

    @Test
    void noTenantBound_failsClosed() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class,
                () -> service.reassign(ORDER, NEW_DRIVER, "x"));
    }

    private void insertDriver(String id, String status) {
        jdbc.update("""
                INSERT INTO drivers (id, name, status, location, updated_at)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(88.36, 22.57), 4326)::geography, now())
                """, id, id, status);
    }

    private void insertOrder(String id, String status, String driver) {
        jdbc.update("""
                INSERT INTO orders (id, customer_name, restaurant, pickup, dropoff,
                                    status, assigned_driver, sla_deadline)
                VALUES (?, 'Test Customer', 'Test Kitchen',
                        ST_SetSRID(ST_MakePoint(88.36, 22.57), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(88.40, 22.60), 4326)::geography,
                        ?, ?, now() + interval '30 minutes')
                """, id, status, driver);
    }
}

package com;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@GraphQlTest({QueryController.class, RelationController.class})
@Import(GraphQlGuardrailsConfig.class)
class QueryGraphTest {

    @Autowired
    GraphQlTester tester;

    @MockitoBean
    OrderQueryRepository orders;

    @MockitoBean
    DriverQueryRepository drivers;

    @MockitoBean
    AlertQueryRepository alerts;

    private static Order order(String id, String driverId) {
        return new Order(id, "cust-" + id, "resto", "ASSIGNED",
                new GeoPoint(22.55, 88.35), new GeoPoint(22.56, 88.36),
                driverId, "2026-09-01T10:00:00Z", null, "2026-09-01T09:00:00Z");
    }

    private static Driver driver(String id) {
        return new Driver(id, "name-" + id, "TO_PICKUP", new GeoPoint(22.55, 88.35), 24.5);
    }

    @Test
    void nestedQueryResolvesDriversWithOneBatchCall() {
        when(orders.findActive(anyInt())).thenReturn(List.of(
                order("o1", "d1"), order("o2", "d2"), order("o3", "d1")));
        when(drivers.findByIds(anyCollection())).thenReturn(List.of(driver("d1"), driver("d2")));
        when(alerts.findOpenByOrderIds(anyCollection())).thenReturn(List.of());

        tester.document("""
                        { activeOrders(limit: 10) { id status driver { id name } alerts { type } } }
                        """)
                .execute()
                .path("activeOrders").entityList(Map.class).hasSize(3)
                .path("activeOrders[0].driver.name").entity(String.class).isEqualTo("name-d1")
                .path("activeOrders[2].driver.id").entity(String.class).isEqualTo("d1")
                .path("activeOrders[0].alerts").entityList(Map.class).hasSize(0);

        verify(drivers, times(1)).findByIds(Set.of("d1", "d2"));
        verify(drivers, never()).findById(anyString());
    }

    @Test
    void unassignedOrderGetsNullDriverAndEmptyAlertList() {
        when(orders.findActive(anyInt())).thenReturn(List.of(order("o1", null)));
        when(alerts.findOpenByOrderIds(anyCollection())).thenReturn(List.of());

        tester.document("""
                        { activeOrders { id driver { id } alerts { type } } }
                        """)
                .execute()
                .path("activeOrders[0].driver").valueIsNull()
                .path("activeOrders[0].alerts").entityList(Map.class).hasSize(0);

        verify(drivers, never()).findByIds(anyCollection());
    }

    @Test
    void alertsAreGroupedToTheRightOrder() {
        when(orders.findActive(anyInt())).thenReturn(List.of(order("o1", "d1"), order("o2", "d2")));
        when(drivers.findByIds(anyCollection())).thenReturn(List.of(driver("d1"), driver("d2")));
        when(alerts.findOpenByOrderIds(anyCollection())).thenReturn(List.of(
                new Alert(1, "SLA_BREACH", "HIGH", "o2", "d2", "late", false, "2026-09-01T09:30:00Z")));

        tester.document("""
                        { activeOrders { id alerts { type severity } } }
                        """)
                .execute()
                .path("activeOrders[0].alerts").entityList(Map.class).hasSize(0)
                .path("activeOrders[1].alerts[0].type").entity(String.class).isEqualTo("SLA_BREACH");
    }

    @Test
    void driverReverseEdgeReturnsActiveOrders() {
        when(drivers.findAll(null)).thenReturn(List.of(driver("d1")));
        when(orders.findActiveByDriverIds(anyCollection())).thenReturn(List.of(order("o1", "d1")));

        tester.document("""
                        { drivers { id activeOrders { id status } } }
                        """)
                .execute()
                .path("drivers[0].activeOrders[0].id").entity(String.class).isEqualTo("o1");

        verify(orders, times(1)).findActiveByDriverIds(Set.of("d1"));
    }

    @Test
    void tooDeepQueryIsRejectedBeforeAnyResolverRuns() {
        tester.document("""
                        { activeOrders { driver { activeOrders { driver { activeOrders { driver {
                          activeOrders { driver { activeOrders { driver { activeOrders { driver {
                          activeOrders { driver { activeOrders { driver { id
                        } } } } } } } } } } } } } } } } }
                        """)
                .execute()
                .errors()
                .expect(e -> e.getMessage() != null && e.getMessage().contains("maximum query depth exceeded"));

        verify(orders, never()).findActive(anyInt());
    }

    @Test
    void isoConversionSurvivesRoundTrip() {
        when(orders.findActive(anyInt())).thenReturn(List.of(order("o1", null)));
        when(alerts.findOpenByOrderIds(anyCollection())).thenReturn(List.of());

        tester.document("{ activeOrders { slaDeadline currentEta } }")
                .execute()
                .path("activeOrders[0].slaDeadline").entity(String.class)
                .satisfies(s -> assertThat(s).isEqualTo("2026-09-01T10:00:00Z"))
                .path("activeOrders[0].currentEta").valueIsNull();
    }
}

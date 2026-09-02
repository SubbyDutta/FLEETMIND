package com;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateTest {

    private final SpringTemplateEngine engine = engine();

    private static SpringTemplateEngine engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var e = new SpringTemplateEngine();
        e.setTemplateResolver(resolver);
        return e;
    }

    private String render(String template, Map<String, Object> model) {
        Context ctx = new Context();
        ctx.setVariables(model);
        return engine.process("email/" + template, ctx);
    }

    @Test
    void alertTemplateRendersAllFields() {
        String html = render("alert", Map.of(
                "type", "SLA_BREACH", "severity", "HIGH",
                "orderId", "order-1", "driverId", "driver-2",
                "reason", "ETA exceeded promised SLA",
                "windowStart", "2026-09-03T10:00:00Z", "windowEnd", "2026-09-03T10:01:00Z"));

        assertThat(html)
                .contains("HIGH · SLA_BREACH")
                .contains("order-1")
                .contains("driver-2")
                .contains("ETA exceeded promised SLA");
    }

    @Test
    void orderStatusTemplateRendersAllFields() {
        String html = render("order-status", Map.of(
                "orderId", "order-9", "status", "DELIVERED",
                "customerName", "Rajdeep", "restaurantName", "Peter Cat",
                "driverId", "driver-5", "slaDeadline", "2026-09-03T11:00:00Z"));

        assertThat(html)
                .contains("Order order-9 — DELIVERED")
                .contains("Rajdeep")
                .contains("Peter Cat");
    }

    @Test
    void dispatchActionTemplateRendersAllFields() {
        String html = render("dispatch-action", Map.of(
                "action", "REASSIGN", "orderId", "order-3",
                "targetId", "driver-7", "requestedTs", "2026-09-03T12:00:00Z"));

        assertThat(html)
                .contains("REASSIGN · order order-3")
                .contains("driver-7");
    }
}

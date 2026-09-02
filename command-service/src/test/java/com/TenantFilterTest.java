package com;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void authenticateAs(String tenant) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"),
                Map.of("sub", "dispatcher@acme.com", "tenant", tenant, "roles", List.of("DISPATCHER")));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void bindsTenantDuringRequest_andClearsAfter() throws Exception {
        authenticateAs("acme");
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seenInsideChain.set(TenantContext.require()));

        assertEquals("acme", seenInsideChain.get());
        assertNull(TenantContext.get());
    }

    @Test
    void clearsTenant_evenWhenChainThrows() {
        authenticateAs("acme");

        assertThrows(ServletException.class, () ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (req, res) -> { throw new ServletException("boom"); }));

        assertNull(TenantContext.get());
    }

    @Test
    void noAuthentication_leavesTenantUnbound_soRequireFailsClosed() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> assertThrows(IllegalStateException.class, TenantContext::require));

        assertNull(TenantContext.get());
    }
}

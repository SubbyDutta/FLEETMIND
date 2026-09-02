package com;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class CurrentTenant {

    private CurrentTenant() {}

    public static String require() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
            String tenant = jwtAuth.getToken().getClaimAsString("tenant");
            if (tenant != null) {
                return tenant;
            }
        }
        throw new IllegalStateException("No tenant in security context — refusing unscoped access");
    }
}

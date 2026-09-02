package com;

public final class TenantContext {
    private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
    private TenantContext(){}
    public static void set(String tenantId) { CURRENT.set(tenantId); }

    public static String require() {
        String tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("No tenant bound to this thread — refusing unscoped access");
        }
        return tenant;
    }

    public static String get() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }
}

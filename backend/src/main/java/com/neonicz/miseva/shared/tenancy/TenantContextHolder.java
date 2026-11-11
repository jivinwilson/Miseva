package com.neonicz.miseva.shared.tenancy;

public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static TenantContext get() {
        TenantContext ctx = CURRENT.get();
        return ctx == null ? TenantContext.NONE : ctx;
    }

    public static void bind(TenantContext ctx) {
        CURRENT.set(ctx);
    }

    static void restore(TenantContext previous) {
        if (previous == null || previous == TenantContext.NONE) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}

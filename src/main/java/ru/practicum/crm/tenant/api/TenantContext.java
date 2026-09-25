package ru.practicum.crm.tenant.api;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CONTEXT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

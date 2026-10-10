package ru.practicum.crm.common.model;

import java.util.UUID;

public class TenantIds {

    public static final String FILTER_NAME = "tenantFilter";
    public static final String PARAM = "tenantId";

    private static final ThreadLocal<UUID> CONTEXT = new ThreadLocal<>();

    public static void set(UUID tenantId) {
        CONTEXT.set(tenantId);
    }

    public static UUID get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static UUID resolve(UUID requested) {
        UUID current = CONTEXT.get();
        return current != null ? current : requested;
    }
}

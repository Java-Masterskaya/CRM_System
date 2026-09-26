package ru.practicum.crm.tenant.api;

import java.util.UUID;

public interface TenantActiveChecker {
    boolean isActive(UUID tenantId);
}

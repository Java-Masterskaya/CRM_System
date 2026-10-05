package ru.practicum.crm.tenant.api;

import java.util.UUID;

public interface TenantContext {

    UUID getCurrentTenantId();

    void setTenantId(UUID tenantId);

    void clear();
}

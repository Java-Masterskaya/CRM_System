package ru.practicum.crm.tenant.api.context;

import java.util.UUID;

public interface TenantContext {
    UUID getCurrentTenantId();
}

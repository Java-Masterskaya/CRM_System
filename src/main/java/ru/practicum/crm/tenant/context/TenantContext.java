package ru.practicum.crm.tenant.context;

import java.util.UUID;

public interface TenantContext {
    UUID getCurrentTenantId();
}

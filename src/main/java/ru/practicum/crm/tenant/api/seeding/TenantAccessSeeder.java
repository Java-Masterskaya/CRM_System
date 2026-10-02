package ru.practicum.crm.tenant.api.seeding;

import java.util.UUID;

/** Явно наполняет арендатора начальными ролями и правами. */
public interface TenantAccessSeeder {

    void seedDefaults(UUID tenantId);
}

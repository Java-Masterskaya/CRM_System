package ru.practicum.crm.tenant.api.seeding;

import java.util.UUID;

/** Явно наполняет арендатора рабочим календарём по умолчанию (T-056). */
public interface TenantCalendarSeeder {

    void seedDefaults(UUID tenantId);
}

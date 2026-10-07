package ru.practicum.crm.tenant.api;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Часовой пояс арендатора из его настроек (T-017). Нужен модулям, которые переводят метки
 * времени из UTC в местное время арендатора, — например, рабочему календарю (T-056).
 */
public interface TenantTimezoneProvider {

    /**
     * Часовой пояс арендатора.
     *
     * @throws ru.practicum.crm.common.error.NotFoundException {@code NOT_FOUND}, если настроек
     *     у арендатора нет
     */
    ZoneId timezoneOf(UUID tenantId);
}

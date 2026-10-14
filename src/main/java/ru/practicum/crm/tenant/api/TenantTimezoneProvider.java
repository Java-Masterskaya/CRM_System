package ru.practicum.crm.tenant.api;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;

/**
 * Часовой пояс арендатора из его настроек (T-017). Нужен модулям, которые переводят метки
 * времени из UTC в местное время арендатора, — например, рабочему календарю (T-056).
 */
public interface TenantTimezoneProvider {

    /**
     * Часовой пояс арендатора.
     *
     * @return пусто, если настроек у арендатора нет
     */
    Optional<ZoneId> findTimezoneOf(UUID tenantId);

    /**
     * Часовой пояс арендатора, который обязан быть.
     *
     * @throws NotFoundException {@code NOT_FOUND}, если настроек у арендатора нет
     */
    default ZoneId timezoneOf(UUID tenantId) {
        return findTimezoneOf(tenantId).orElseThrow(() -> new NotFoundException(
                ErrorCode.NOT_FOUND, "Настройки арендатора не найдены."));
    }
}

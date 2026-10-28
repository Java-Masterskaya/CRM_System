package ru.practicum.crm.sla.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Политика сроков арендатора (T-055).
 *
 * @param defaultPolicy политика по умолчанию: тип и приоритет пусты, сроки применяются, когда
 *     для пары своей политики нет
 */
public record SlaPolicyResponse(
        UUID id,
        UUID typeId,
        RequestPriority priority,
        boolean defaultPolicy,
        int firstResponseMinutes,
        int resolutionMinutes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

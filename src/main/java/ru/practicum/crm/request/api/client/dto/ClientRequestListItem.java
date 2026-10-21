package ru.practicum.crm.request.api.client.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Краткая информация о заявке для клиентского списка.
 * Не включает сведения об исполнителе, внутренних комментариях и
 * служебных признаках обработки.
 */
public record ClientRequestListItem(
        UUID id,
        String subject,
        RequestStatus status,
        RequestPriority priority,
        UUID typeId,
        Instant desiredDueAt,
        OffsetDateTime createdAt
) {
}

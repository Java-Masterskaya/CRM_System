package ru.practicum.crm.request.api.dto;

import java.time.Instant;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Тип заявки в административном контуре (T-034): со служебными полями, включая отключённые типы.
 *
 * @param active можно ли выбрать тип для новой заявки
 */
public record RequestTypeResponse(
        UUID id,
        String name,
        String description,
        RequestPriority defaultPriority,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}

package ru.practicum.crm.request.api.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Строка реестра заявок (T-040) и очереди оператора (T-052): то, по чему оператор выбирает
 * заявку для работы. Полная карточка — отдельной операцией (T-041).
 *
 * @param overdue просрочена сейчас — тот же признак, по которому работает фильтр «просроченные»
 * @param version версия заявки: с ней можно сразу менять статус из реестра (T-044, T-045)
 */
public record RequestRegistryItem(
        UUID id,
        String subject,
        RequestStatus status,
        RequestPriority priority,
        UUID typeId,
        UUID authorId,
        UUID assigneeId,
        OffsetDateTime createdAt,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        boolean overdue,
        long version
) {
}

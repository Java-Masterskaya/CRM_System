package ru.practicum.crm.request.api;

import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;

public interface RequestAssignmentService {

    RequestAssignmentDto changeAssignee(UUID tenantId, UUID requestId, UUID assigneeId,
                                        long expectedVersion, UUID actorId);

    RequestAssignmentOutcome takeUnassigned(UUID tenantId, UUID requestId, UUID actorId);

    /**
     * Текущая нагрузка исполнителей арендатора (T-051): сколько у каждого незавершённых заявок.
     *
     * <p>Считается одним агрегирующим запросом с группировкой по исполнителю — не по запросу на
     * кандидата. Нагрузкой считаются заявки в незавершённых статусах: терминальные уже не работа.
     *
     * @return число незавершённых заявок по идентификатору исполнителя; исполнителей без таких
     *     заявок в ответе нет — их нагрузка нулевая
     */
    Map<UUID, Long> activeWorkloadByAssignee(UUID tenantId);
}

package ru.practicum.crm.request.api;

import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;

public interface RequestAssignmentService {

    /**
     * Назначает, переназначает или снимает исполнителя и сообщает, изменилось ли назначение.
     *
     * <p>{@code changed} в ответе нужен вызывающему, чтобы не отправлять уведомление, когда
     * исполнитель остался прежним.
     */
    RequestAssignmentOutcome changeAssignee(UUID tenantId, UUID requestId, UUID assigneeId,
                                            long expectedVersion, UUID actorId);

    /**
     * Проверяет, что заявку ещё можно назначить автоподбором: она существует, не изменилась
     * и у неё нет исполнителя.
     *
     * <p>Автоподбор не переназначает исполнителя. Иначе повторный вызов переносил бы заявку
     * к другому: нагрузка текущего исполнителя уже включает её саму, и при равенстве или
     * разнице в одну заявку выбор уходил бы другому. Для смены исполнителя есть ручное
     * назначение.
     *
     * @throws ru.practicum.crm.common.error.NotFoundException если заявка не найдена
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException если версия
     *     заявки устарела
     * @throws ru.practicum.crm.common.error.ApiException {@code REQUEST_ALREADY_ASSIGNED},
     *     если исполнитель уже назначен
     */
    void requireUnassigned(UUID tenantId, UUID requestId, long expectedVersion);

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

package ru.practicum.crm.request.repository;

import java.util.UUID;

/**
 * Строка агрегата нагрузки исполнителя (T-051): сколько у него незавершённых заявок.
 *
 * <p>Проекция одного агрегирующего запроса {@link RequestRepository#findWorkloadByAssignee}.
 * Отдельная сущность для неё не нужна: это результат подсчёта, а не хранимые данные.
 */
public interface AssigneeWorkload {

    /** Идентификатор исполнителя. */
    UUID getAssigneeId();

    /** Число незавершённых заявок исполнителя. */
    long getActiveRequests();
}

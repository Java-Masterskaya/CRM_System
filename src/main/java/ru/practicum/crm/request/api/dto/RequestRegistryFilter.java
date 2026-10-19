package ru.practicum.crm.request.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Фильтры реестра заявок (T-040). Пустое поле — фильтр не задан; заданные складываются через «и».
 *
 * @param statuses заявки в любом из этих статусов
 * @param typeId заявки этого типа
 * @param priority заявки с этим приоритетом
 * @param createdFrom созданы не раньше этого момента
 * @param createdTo созданы раньше этого момента: граница в период не входит, поэтому соседние
 *     периоды не пересекаются
 * @param overdueOnly только просроченные сейчас (T-060): закрытые с нарушением сюда не входят
 */
public record RequestRegistryFilter(
        Set<RequestStatus> statuses,
        UUID typeId,
        RequestPriority priority,
        Instant createdFrom,
        Instant createdTo,
        boolean overdueOnly
) {

    public RequestRegistryFilter {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}

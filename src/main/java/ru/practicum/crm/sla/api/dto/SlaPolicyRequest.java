package ru.practicum.crm.sla.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Новая политика сроков (T-055). Тип и приоритет заданы — политика для этой пары; оба не заданы —
 * политика по умолчанию. Задан только один из двух — ошибка в поле, которого не хватает.
 *
 * <p>Допустимые значения сроков (больше нуля, не больше года, первый ответ не позже решения)
 * проверяет сервис — те же правила действуют при изменении сроков.
 *
 * @param firstResponseMinutes срок первого ответа в минутах рабочего времени
 * @param resolutionMinutes срок решения в минутах рабочего времени
 */
public record SlaPolicyRequest(
        UUID typeId,
        RequestPriority priority,
        @NotNull
        Integer firstResponseMinutes,
        @NotNull
        Integer resolutionMinutes
) {

    /** Тип и приоритет не заданы — значит, политика по умолчанию. */
    public boolean forDefaultPolicy() {
        return typeId == null && priority == null;
    }
}

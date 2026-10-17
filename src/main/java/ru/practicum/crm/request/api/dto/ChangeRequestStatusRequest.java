package ru.practicum.crm.request.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Перевод заявки в другой статус (T-044).
 *
 * @param status целевой статус; допустим ли переход, решает матрица переходов (T-043)
 * @param comment комментарий к переходу; при переводе в {@code REJECTED} — обязательная причина
 * @param version версия заявки, которую видел пользователь (T-045): если заявку успели изменить,
 *     перевод отклоняется, а не затирает чужое изменение
 */
public record ChangeRequestStatusRequest(
        @NotNull
        RequestStatus status,
        @Size(max = 4000)
        String comment,
        @NotNull
        Long version
) {
}

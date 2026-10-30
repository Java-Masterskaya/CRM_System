package ru.practicum.crm.sla.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Новые сроки политики (T-055). Тип и приоритет политики не меняются: для другой пары создаётся
 * другая политика.
 *
 * @param firstResponseMinutes срок первого ответа в минутах рабочего времени
 * @param resolutionMinutes срок решения в минутах рабочего времени
 */
public record SlaTermsRequest(
        @NotNull
        Integer firstResponseMinutes,
        @NotNull
        Integer resolutionMinutes
) {
}

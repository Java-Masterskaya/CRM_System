package ru.practicum.crm.sla.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Запись справочника нерабочих дней арендатора (T-057).
 *
 * @param working рабочий день с часами {@code start}–{@code end}; иначе дата нерабочая и часов
 *     нет
 */
public record HolidayResponse(
        UUID id,
        LocalDate date,
        String description,
        boolean working,
        LocalTime start,
        LocalTime end,
        OffsetDateTime createdAt
) {
}

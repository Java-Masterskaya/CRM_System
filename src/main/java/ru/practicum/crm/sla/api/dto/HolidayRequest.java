package ru.practicum.crm.sla.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Новая запись справочника нерабочих дней (T-057). Без признака {@code working} — нерабочий день:
 * праздник или корпоративный выходной, часов у него нет. С {@code working: true} — рабочий день с
 * этими часами: перенос с выходного или сокращённый день.
 *
 * <p>Обязательность даты, описания и часов рабочего дня проверяет сервис и указывает поле.
 *
 * @param start начало рабочего дня; только у рабочего дня
 * @param end конец рабочего дня, не включается; только у рабочего дня
 */
public record HolidayRequest(
        LocalDate date,
        String description,
        boolean working,
        LocalTime start,
        LocalTime end
) {
}

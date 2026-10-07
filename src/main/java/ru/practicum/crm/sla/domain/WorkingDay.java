package ru.practicum.crm.sla.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Рабочие часы в один день недели, в местном времени арендатора: с {@code start} включительно
 * до {@code end} не включительно. Интервал на день один и через полночь не переходит.
 *
 * @param dayOfWeek день недели
 * @param start начало рабочего дня
 * @param end конец рабочего дня; сам этот момент уже нерабочий
 */
public record WorkingDay(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {

    /** Входит ли время суток в рабочие часы этого дня. */
    public boolean contains(LocalTime time) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}

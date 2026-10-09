package ru.practicum.crm.sla.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Что календарь знает о дате из справочника нерабочих дней (T-057). Без часов — день
 * нерабочий; с часами — рабочий с {@code start} включительно до {@code end} не включительно,
 * как бы ни был устроен этот день недели в календаре. Дата и часы — местные для арендатора.
 *
 * @param date дата
 * @param start начало рабочего дня; пусто, если день нерабочий
 * @param end конец рабочего дня; пусто, если день нерабочий
 */
public record DateOverride(LocalDate date, LocalTime start, LocalTime end) {
}

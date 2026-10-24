package ru.practicum.crm.sla.api.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Рабочие часы арендатора по дням недели (T-056) — и в ответе, и в запросе на замену. Дня нет в
 * списке — день выходной. Время — местное, в часовом поясе из настроек арендатора.
 *
 * <p>Неделя — одна настройка не больше чем из семи дней, а не растущий список, поэтому она
 * отдаётся целиком, без страниц. Правила (хотя бы один рабочий день, день недели не повторяется,
 * начало раньше конца) проверяет сервис и указывает поле, например {@code #/days/0/end}.
 */
public record WorkingWeekDto(List<Day> days) {

    public WorkingWeekDto {
        days = days == null ? null : List.copyOf(days);
    }

    /**
     * Рабочие часы одного дня: с {@code start} включительно до {@code end} не включительно.
     */
    public record Day(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
    }
}

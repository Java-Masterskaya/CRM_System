package ru.practicum.crm.sla.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Рабочий календарь арендатора (T-056): рабочие часы по дням недели и часовой пояс, в котором
 * они действуют.
 *
 * <p>Метки времени в системе хранятся в UTC, а рабочие часы применяются в местном времени
 * арендатора. Поэтому переход на летнее время не сдвигает рабочий день: 09:00 остаётся 09:00 по
 * местным часам, хотя в UTC это зимой и летом разные моменты.
 *
 * <p>Календарь загружается один раз и дальше к базе не обращается: расчёт сроков (T-058)
 * работает с ним как с обычным объектом.
 */
public final class WorkingCalendar {

    private final ZoneId zone;
    private final Map<DayOfWeek, WorkingDay> days = new EnumMap<>(DayOfWeek.class);

    /**
     * Календарь из рабочих дней недели.
     *
     * @param zone часовой пояс арендатора
     * @param days рабочие дни, не больше одного на день недели; дня нет — день выходной
     */
    public WorkingCalendar(ZoneId zone, Collection<WorkingDay> days) {
        this.zone = zone;
        for (WorkingDay day : days) {
            this.days.put(day.dayOfWeek(), day);
        }
    }

    public ZoneId getZone() {
        return zone;
    }

    /** Рабочие дни по порядку, начиная с понедельника. */
    public List<WorkingDay> getDays() {
        return List.copyOf(days.values());
    }

    /**
     * Является ли момент рабочим временем. День недели и время суток определяются в часовом
     * поясе арендатора: поздний вечер воскресенья по UTC в Москве — уже понедельник.
     */
    public boolean isWorkingTime(Instant moment) {
        ZonedDateTime local = moment.atZone(zone);
        WorkingDay day = days.get(local.getDayOfWeek());
        return day != null && day.contains(local.toLocalTime());
    }
}

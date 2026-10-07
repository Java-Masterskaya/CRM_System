package ru.practicum.crm.sla.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рабочий календарь арендатора: рабочие часы по дням недели (T-056), даты из справочника
 * нерабочих дней (T-057) и часовой пояс, в котором всё это действует.
 *
 * <p>Метки времени в системе хранятся в UTC, а рабочие часы применяются в местном времени
 * арендатора. Поэтому переход на летнее время не сдвигает рабочий день: 09:00 остаётся 09:00 по
 * местным часам, хотя в UTC это зимой и летом разные моменты.
 *
 * <p>Дата из справочника важнее дня недели: праздник в понедельник — нерабочий день, перенос на
 * субботу — рабочий с часами из справочника.
 *
 * <p>Календарь загружается один раз и дальше к базе не обращается: расчёт сроков (T-058)
 * работает с ним как с обычным объектом.
 */
public final class WorkingCalendar {

    private final ZoneId zone;
    private final Map<DayOfWeek, WorkingDay> days = new EnumMap<>(DayOfWeek.class);
    private final Map<LocalDate, DateOverride> overrides = new HashMap<>();

    /**
     * Календарь только из рабочих дней недели, без справочника нерабочих дней.
     *
     * @param zone часовой пояс арендатора
     * @param days рабочие дни, не больше одного на день недели; дня нет — день выходной
     */
    public WorkingCalendar(ZoneId zone, Collection<WorkingDay> days) {
        this(zone, days, List.of());
    }

    /**
     * Календарь из рабочих дней недели и дат справочника нерабочих дней.
     *
     * @param zone часовой пояс арендатора
     * @param days рабочие дни, не больше одного на день недели; дня нет — день выходной
     * @param overrides даты справочника, не больше одной записи на дату
     */
    public WorkingCalendar(ZoneId zone, Collection<WorkingDay> days,
            Collection<DateOverride> overrides) {
        this.zone = zone;
        for (WorkingDay day : days) {
            this.days.put(day.dayOfWeek(), day);
        }
        for (DateOverride override : overrides) {
            this.overrides.put(override.date(), override);
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
     * Является ли момент рабочим временем. Дата, день недели и время суток определяются в
     * часовом поясе арендатора: поздний вечер воскресенья по UTC в Москве — уже понедельник.
     * Если дата есть в справочнике нерабочих дней, решает справочник, а не день недели.
     */
    public boolean isWorkingTime(Instant moment) {
        ZonedDateTime local = moment.atZone(zone);
        DateOverride override = overrides.get(local.toLocalDate());
        if (override != null) {
            return override.contains(local.toLocalTime());
        }
        WorkingDay day = days.get(local.getDayOfWeek());
        return day != null && day.contains(local.toLocalTime());
    }
}

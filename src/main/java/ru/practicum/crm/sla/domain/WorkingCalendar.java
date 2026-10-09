package ru.practicum.crm.sla.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>Календарь загружается один раз и дальше к базе не обращается: и проверка рабочего времени,
 * и сложение рабочих минут для сроков (T-058) — чистая арифметика.
 */
public final class WorkingCalendar {

    private final ZoneId zone;
    private final Map<DayOfWeek, WorkingDay> days = new EnumMap<>(DayOfWeek.class);
    private final Map<LocalDate, DateOverride> overrides = new HashMap<>();
    /** Последняя рабочая дата справочника — нужна, только если рабочих дней недели нет. */
    private LocalDate lastWorkingOverride;

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
            if (override.start() != null && (lastWorkingOverride == null
                    || override.date().isAfter(lastWorkingOverride))) {
                lastWorkingOverride = override.date();
            }
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
        return hoursOn(local.toLocalDate())
                .map(hours -> hours.contains(local.toLocalTime()))
                .orElse(false);
    }

    /**
     * Момент, когда истекут {@code minutes} рабочих минут, отсчитанных от {@code from}: время
     * вне рабочих часов, выходные и даты справочника пропускаются. Если {@code from} попадает
     * на нерабочее время, отсчёт начинается с ближайшего начала рабочего дня. Срок, который
     * кончается ровно в конце рабочего дня, — этот конец, а не начало следующего дня.
     *
     * <p>Минуты считаются по местным часам арендатора, с точностью до долей секунды: заявка,
     * созданная в 17:00:30, со сроком в один час при дне до 18:00 получит срок 09:00:30
     * следующего рабочего дня.
     *
     * @param minutes сколько рабочих минут отсчитать; больше нуля
     * @return пусто, если впереди нет рабочего времени: у календаря нет рабочих дней недели,
     *     а рабочие даты справочника, если есть, уже позади
     * @throws IllegalArgumentException если {@code minutes} не больше нуля
     */
    public Optional<Instant> plusWorkingMinutes(Instant from, long minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("Срок в рабочих минутах должен быть больше нуля");
        }
        Duration remaining = Duration.ofMinutes(minutes);
        ZonedDateTime start = from.atZone(zone);
        LocalDate date = start.toLocalDate();
        LocalTime time = start.toLocalTime();
        while (hasWorkingTimeFrom(date)) {
            Optional<WorkingDay> hours = hoursOn(date);
            if (hours.isPresent() && time.isBefore(hours.get().end())) {
                LocalTime begin = time.isAfter(hours.get().start()) ? time : hours.get().start();
                Duration available = Duration.between(begin, hours.get().end());
                if (remaining.compareTo(available) <= 0) {
                    return Optional.of(ZonedDateTime.of(date, begin.plus(remaining), zone)
                            .toInstant());
                }
                remaining = remaining.minus(available);
            }
            date = date.plusDays(1);
            time = LocalTime.MIDNIGHT;
        }
        return Optional.empty();
    }

    /**
     * Рабочие часы даты: из справочника, если дата там есть, иначе — её дня недели. Пусто —
     * день нерабочий.
     */
    private Optional<WorkingDay> hoursOn(LocalDate date) {
        DateOverride override = overrides.get(date);
        if (override == null) {
            return Optional.ofNullable(days.get(date.getDayOfWeek()));
        }
        if (override.start() == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkingDay(date.getDayOfWeek(), override.start(), override.end()));
    }

    /**
     * Есть ли рабочее время с этой даты и дальше. Если есть рабочие дни недели — есть всегда:
     * дат в справочнике конечное число, и они не могут закрыть все будущие недели. Если рабочих
     * дней недели нет, рабочими остаются только даты справочника, и после последней из них
     * считать сроки не по чему — без этой проверки сложение не закончилось бы никогда.
     */
    private boolean hasWorkingTimeFrom(LocalDate date) {
        return !days.isEmpty()
                || (lastWorkingOverride != null && !date.isAfter(lastWorkingOverride));
    }
}

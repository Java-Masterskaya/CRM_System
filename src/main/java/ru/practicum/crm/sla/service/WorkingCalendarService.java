package ru.practicum.crm.sla.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ConstraintViolations;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.domain.WorkingCalendar;
import ru.practicum.crm.sla.domain.WorkingDay;
import ru.practicum.crm.sla.domain.WorkingHours;
import ru.practicum.crm.sla.repository.HolidayRepository;
import ru.practicum.crm.sla.repository.WorkingHoursRepository;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;
import ru.practicum.crm.tenant.api.seeding.TenantCalendarSeeder;

/**
 * Рабочий календарь арендатора (T-056): календарь по умолчанию для нового арендатора, просмотр
 * и замена рабочих часов, календарь для расчёта сроков.
 *
 * <p>Кто вправе менять календарь, решает вызывающий код: по ТЗ §6.3 это администратор
 * арендатора — права (T-030). Все операции работают в пределах арендатора.
 *
 * <p>Календарь по умолчанию записывается при создании арендатора: {@code TenantService} явно
 * вызывает {@link #seedDefaults} в той же транзакции — так же, как наполняет роли и права
 * (T-021). Арендаторам, которые были до этой задачи, его записала миграция.
 */
@Service
public class WorkingCalendarService implements TenantCalendarSeeder {

    private static final String DAYS_FIELD = "days";
    private static final String DAY_CONSTRAINT = "working_hours_day_unique";

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(18, 0);

    private static final Comparator<WorkingDay> FROM_MONDAY =
            Comparator.comparing(WorkingDay::dayOfWeek);

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Репозиторий — Spring-бин, внедряется как зависимость. SpotBugs "
                    + "считает его изменяемым из-за метода deleteByTenantId")
    private final WorkingHoursRepository repository;
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Репозиторий — Spring-бин, внедряется как зависимость. SpotBugs "
                    + "считает его изменяемым из-за метода delete")
    private final HolidayRepository holidays;
    private final TenantTimezoneProvider timezones;

    public WorkingCalendarService(WorkingHoursRepository repository, HolidayRepository holidays,
            TenantTimezoneProvider timezones) {
        this.repository = repository;
        this.holidays = holidays;
        this.timezones = timezones;
    }

    /**
     * Записывает новому арендатору календарь по умолчанию: понедельник–пятница, 09:00–18:00.
     * Если календарь у арендатора уже есть, ничего не меняет — повторный вызов не затрёт
     * настройки администратора.
     */
    @Override
    @Transactional
    public void seedDefaults(UUID tenantId) {
        if (!repository.existsByTenantId(tenantId)) {
            repository.saveAllAndFlush(defaultWeek().stream()
                    .map(day -> new WorkingHours(tenantId, day))
                    .toList());
        }
    }

    /** Рабочие дни арендатора по порядку, с понедельника; дня нет в списке — день выходной. */
    @Transactional(readOnly = true)
    public List<WorkingDay> workingDays(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream()
                .map(WorkingHours::toWorkingDay)
                .sorted(FROM_MONDAY)
                .toList();
    }

    /**
     * Заменяет календарь арендатора целиком: дни, которых нет в списке, становятся выходными.
     *
     * @param days рабочие дни, не больше одного на день недели
     * @return сохранённые рабочие дни по порядку, с понедельника
     * @throws ApiException {@code VALIDATION_FAILED} с перечнем полей, если рабочих дней нет,
     *     день недели повторяется или начало дня не раньше его конца; {@code STALE_VERSION},
     *     если календарь в это же время заменил другой запрос
     */
    @Transactional
    public List<WorkingDay> changeWorkingDays(UUID tenantId, List<WorkingDay> days) {
        validate(days);
        repository.deleteByTenantId(tenantId);
        try {
            repository.saveAllAndFlush(days.stream()
                    .map(day -> new WorkingHours(tenantId, day))
                    .toList());
        } catch (DataIntegrityViolationException ex) {
            if (DAY_CONSTRAINT.equals(ConstraintViolations.nameOf(ex))) {
                throw new ApiException(ErrorCode.STALE_VERSION,
                        "Календарь в это же время изменён другим запросом.");
            }
            throw ex;
        }
        return days.stream().sorted(FROM_MONDAY).toList();
    }

    /**
     * Календарь для расчётов: рабочие дни, справочник нерабочих дней (T-057) и часовой пояс из
     * настроек арендатора.
     */
    @Transactional(readOnly = true)
    public WorkingCalendar calendar(UUID tenantId) {
        return build(tenantId, timezones.timezoneOf(tenantId));
    }

    /**
     * Календарь для расчёта сроков (T-058): то же, что {@link #calendar}, но без исключения,
     * если у арендатора нет настроек. Исключение, вылетевшее из транзакционного метода, Spring
     * считает сбоем всей транзакции вызывающего и помечает её на откат — тогда не создалась бы
     * и заявка, которой сроки считаются.
     *
     * @return пусто, если у арендатора нет настроек
     */
    @Transactional(readOnly = true)
    public Optional<WorkingCalendar> findCalendar(UUID tenantId) {
        return timezones.findTimezoneOf(tenantId).map(zone -> build(tenantId, zone));
    }

    private WorkingCalendar build(UUID tenantId, ZoneId zone) {
        return new WorkingCalendar(zone, workingDays(tenantId),
                holidays.findByTenantId(tenantId).stream().map(Holiday::toDateOverride).toList());
    }

    /** Неделя по умолчанию: понедельник–пятница, 09:00–18:00. */
    private static List<WorkingDay> defaultWeek() {
        return Stream.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                .map(day -> new WorkingDay(day, DEFAULT_START, DEFAULT_END))
                .toList();
    }

    /** Собирает все ошибки сразу, чтобы вызывающий получил полный перечень полей. */
    private static void validate(List<WorkingDay> days) {
        List<ValidationError> errors = new ArrayList<>();
        if (days == null || days.isEmpty()) {
            errors.add(ValidationError.ofField(DAYS_FIELD, "нужен хотя бы один рабочий день"));
        } else {
            Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
            for (int i = 0; i < days.size(); i++) {
                collectDayErrors(days.get(i), DAYS_FIELD + "[" + i + "]", seen, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }

    private static void collectDayErrors(WorkingDay day, String path, Set<DayOfWeek> seen,
            List<ValidationError> errors) {
        if (day == null) {
            errors.add(ValidationError.ofField(path, "не должно быть пустым"));
            return;
        }
        if (day.dayOfWeek() == null) {
            errors.add(ValidationError.ofField(path + ".dayOfWeek", "не должно быть пустым"));
        } else if (!seen.add(day.dayOfWeek())) {
            errors.add(ValidationError.ofField(path + ".dayOfWeek",
                    "этот день недели уже указан"));
        }
        if (day.start() == null) {
            errors.add(ValidationError.ofField(path + ".start", "не должно быть пустым"));
        }
        if (day.end() == null) {
            errors.add(ValidationError.ofField(path + ".end", "не должно быть пустым"));
        }
        if (day.start() != null && day.end() != null && !day.start().isBefore(day.end())) {
            errors.add(ValidationError.ofField(path + ".end", "должно быть позже начала"));
        }
    }
}

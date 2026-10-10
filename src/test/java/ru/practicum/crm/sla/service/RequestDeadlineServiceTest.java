package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.sla.api.RequestDeadlines;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.domain.WorkingCalendar;
import ru.practicum.crm.sla.domain.WorkingDay;

/**
 * Расчёт сроков без базы: политика и календарь — заглушки. Арифметику календаря проверяет
 * {@code WorkingCalendarTest}, здесь — выбор политики, оба срока и случаи без сроков.
 */
class RequestDeadlineServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TYPE_ID = UUID.randomUUID();
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final WorkingCalendar WEEKDAYS = new WorkingCalendar(MOSCOW,
            Stream.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                    .map(day -> new WorkingDay(day, LocalTime.of(9, 0), LocalTime.of(18, 0)))
                    .toList());
    /** Понедельник, 5 октября 2026 года, 17:00 по Москве — за час до конца рабочего дня. */
    private static final Instant MONDAY_FIVE_PM = moscowTime(5, 17);

    private final SlaPolicyService policies = mock(SlaPolicyService.class);
    private final WorkingCalendarService calendars = mock(WorkingCalendarService.class);
    private final RequestDeadlineService service =
            new RequestDeadlineService(policies, calendars);

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestDeadlineService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    /** Час первого ответа и 8 часов решения с 17:00 понедельника: 18:00 и 16:00 вторника. */
    @Test
    void calculate_withPolicyAndCalendar_countsBothTermsInWorkingTime() {
        when(policies.resolve(TENANT_ID, TYPE_ID, RequestPriority.HIGH)).thenReturn(Optional.of(
                SlaPolicy.forPair(TENANT_ID, TYPE_ID, RequestPriority.HIGH,
                        new SlaTerms(60, 480))));
        when(calendars.findCalendar(TENANT_ID, MONDAY_FIVE_PM)).thenReturn(Optional.of(WEEKDAYS));

        assertThat(service.calculate(TENANT_ID, TYPE_ID, RequestPriority.HIGH, MONDAY_FIVE_PM))
                .contains(new RequestDeadlines(moscowTime(5, 18), moscowTime(6, 16)));
        assertThat(logs.list).isEmpty();
    }

    @Test
    void calculate_withoutPolicy_isEmptyAndLoggedWithoutLoadingCalendar() {
        when(policies.resolve(TENANT_ID, TYPE_ID, RequestPriority.HIGH))
                .thenReturn(Optional.empty());

        assertThat(service.calculate(TENANT_ID, TYPE_ID, RequestPriority.HIGH, MONDAY_FIVE_PM))
                .isEmpty();

        verifyNoInteractions(calendars);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("нет политики",
                    TENANT_ID.toString(), TYPE_ID.toString(), "HIGH");
        });
    }

    @Test
    void calculate_whenTenantHasNoSettings_isEmptyAndWarned() {
        when(policies.resolve(TENANT_ID, null, RequestPriority.NORMAL)).thenReturn(Optional.of(
                SlaPolicy.byDefault(TENANT_ID, new SlaTerms(60, 480))));
        when(calendars.findCalendar(TENANT_ID, MONDAY_FIVE_PM)).thenReturn(Optional.empty());

        assertThat(service.calculate(TENANT_ID, null, RequestPriority.NORMAL, MONDAY_FIVE_PM))
                .isEmpty();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("нет настроек",
                    TENANT_ID.toString());
        });
    }

    @Test
    void calculate_whenCalendarHasNoWorkingTime_isEmptyAndWarned() {
        when(policies.resolve(TENANT_ID, null, RequestPriority.NORMAL)).thenReturn(Optional.of(
                SlaPolicy.byDefault(TENANT_ID, new SlaTerms(60, 480))));
        when(calendars.findCalendar(TENANT_ID, MONDAY_FIVE_PM))
                .thenReturn(Optional.of(new WorkingCalendar(MOSCOW, List.of())));

        assertThat(service.calculate(TENANT_ID, null, RequestPriority.NORMAL, MONDAY_FIVE_PM))
                .isEmpty();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("не хватает рабочего времени",
                    "в пределах 10 лет", TENANT_ID.toString());
        });
    }

    /**
     * Минута рабочего времени в неделю: срок в год минут набрался бы только через десять тысяч
     * лет — расчёт останавливается на пределе календаря, а не перебирает дни до конца.
     */
    @Test
    void calculate_whenTermDoesNotFitIntoTenYears_isEmptyAndWarned() {
        SlaPolicy policy = SlaPolicy.byDefault(TENANT_ID,
                new SlaTerms(SlaTerms.MAX_MINUTES, SlaTerms.MAX_MINUTES));
        when(policies.resolve(TENANT_ID, null, RequestPriority.NORMAL))
                .thenReturn(Optional.of(policy));
        when(calendars.findCalendar(TENANT_ID, MONDAY_FIVE_PM)).thenReturn(Optional.of(
                new WorkingCalendar(MOSCOW, List.of(new WorkingDay(DayOfWeek.MONDAY,
                        LocalTime.of(9, 0), LocalTime.of(9, 1))))));

        assertThat(service.calculate(TENANT_ID, null, RequestPriority.NORMAL, MONDAY_FIVE_PM))
                .isEmpty();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("не хватает рабочего времени",
                    TENANT_ID.toString());
        });
    }

    private static Instant moscowTime(int dayOfOctober, int hour) {
        return ZonedDateTime.of(2026, 10, dayOfOctober, hour, 0, 0, 0, MOSCOW).toInstant();
    }
}

package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.sla.domain.WorkingCalendar;
import ru.practicum.crm.sla.domain.WorkingDay;
import ru.practicum.crm.sla.domain.WorkingHours;
import ru.practicum.crm.sla.repository.WorkingHoursRepository;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;

/**
 * Сервис без базы: репозиторий и часовой пояс арендатора — заглушки; хранение и ограничения
 * базы проверяет интеграционный тест.
 */
class WorkingCalendarServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);
    private static final WorkingDay MONDAY = new WorkingDay(DayOfWeek.MONDAY, NINE, SIX_PM);
    private static final WorkingDay FRIDAY =
            new WorkingDay(DayOfWeek.FRIDAY, LocalTime.of(10, 0), LocalTime.of(16, 0));

    private final WorkingHoursRepository repository = mock(WorkingHoursRepository.class);
    private final TenantTimezoneProvider timezones = mock(TenantTimezoneProvider.class);
    private final WorkingCalendarService service =
            new WorkingCalendarService(repository, timezones);

    @Test
    void seedDefaults_forTenantWithoutCalendar_storesMondayToFridayNineToSix() {
        when(repository.existsByTenantId(TENANT_ID)).thenReturn(false);

        service.seedDefaults(TENANT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<WorkingHours>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).extracting(WorkingHours::toWorkingDay)
                .extracting(WorkingDay::dayOfWeek)
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(saved.getValue()).extracting(WorkingHours::toWorkingDay)
                .allMatch(day -> day.start().equals(NINE) && day.end().equals(SIX_PM));
    }

    @Test
    void seedDefaults_whenTenantAlreadyHasCalendar_keepsIt() {
        when(repository.existsByTenantId(TENANT_ID)).thenReturn(true);

        service.seedDefaults(TENANT_ID);

        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void workingDays_whenNothingStored_hasNoWorkingDays() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(List.of());

        assertThat(service.workingDays(TENANT_ID)).isEmpty();
    }

    @Test
    void workingDays_whenCalendarSaved_areStoredDaysFromMonday() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(List.of(
                new WorkingHours(TENANT_ID, FRIDAY), new WorkingHours(TENANT_ID, MONDAY)));

        assertThat(service.workingDays(TENANT_ID)).containsExactly(MONDAY, FRIDAY);
    }

    @Test
    void calendar_combinesWorkingDaysWithTenantTimezone() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(
                List.of(new WorkingHours(TENANT_ID, MONDAY)));
        when(timezones.timezoneOf(TENANT_ID)).thenReturn(ZoneId.of("Asia/Yekaterinburg"));

        WorkingCalendar calendar = service.calendar(TENANT_ID);

        assertThat(calendar.getZone()).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
        assertThat(calendar.getDays()).containsExactly(MONDAY);
    }

    @Test
    void changeWorkingDays_whenValid_replacesWholeCalendarOfTenant() {
        List<WorkingDay> saved = service.changeWorkingDays(TENANT_ID, List.of(FRIDAY, MONDAY));

        assertThat(saved).containsExactly(MONDAY, FRIDAY);
        InOrder order = inOrder(repository);
        order.verify(repository).deleteByTenantId(TENANT_ID);
        order.verify(repository).saveAllAndFlush(any());
    }

    @Test
    void changeWorkingDays_whenStartIsNotBeforeEnd_isRejectedWithEndFieldAndNothingWritten() {
        List<WorkingDay> days = List.of(MONDAY,
                new WorkingDay(DayOfWeek.TUESDAY, SIX_PM, NINE),
                new WorkingDay(DayOfWeek.WEDNESDAY, NINE, NINE));

        ApiException thrown = catchThrowableOfType(
                () -> service.changeWorkingDays(TENANT_ID, days), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("days[1].end", "должно быть позже начала"),
                ValidationError.ofField("days[2].end", "должно быть позже начала"));
        verifyNoInteractions(repository);
    }

    @Test
    void changeWorkingDays_withoutWorkingDays_isRejected() {
        ApiException thrown = catchThrowableOfType(
                () -> service.changeWorkingDays(TENANT_ID, List.of()), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("days", "нужен хотя бы один рабочий день"));
        verifyNoInteractions(repository);
    }

    @Test
    void changeWorkingDays_whenDayOfWeekRepeated_isRejectedAtRepetition() {
        ApiException thrown = catchThrowableOfType(() -> service.changeWorkingDays(TENANT_ID,
                List.of(MONDAY, FRIDAY, MONDAY)), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("days[2].dayOfWeek", "этот день недели уже указан"));
        verifyNoInteractions(repository);
    }

    @Test
    void changeWorkingDays_whenValuesMissing_isRejectedWithAllOfThem() {
        List<WorkingDay> days = Arrays.asList(new WorkingDay(null, null, null), null);

        ApiException thrown = catchThrowableOfType(
                () -> service.changeWorkingDays(TENANT_ID, days), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("days[0].dayOfWeek", "не должно быть пустым"),
                ValidationError.ofField("days[0].start", "не должно быть пустым"),
                ValidationError.ofField("days[0].end", "не должно быть пустым"),
                ValidationError.ofField("days[1]", "не должно быть пустым"));
        verifyNoInteractions(repository);
    }

    /**
     * Две замены календаря одновременно: вторая удаляет старые строки, но строки первой уже
     * вставлены, и вставка того же дня нарушает уникальность.
     */
    @Test
    void changeWorkingDays_whenCalendarChangedConcurrently_isRejectedAsStale() {
        when(repository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"working_hours_day_unique\""));

        ApiException thrown = catchThrowableOfType(
                () -> service.changeWorkingDays(TENANT_ID, List.of(MONDAY)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.STALE_VERSION);
    }

    @Test
    void changeWorkingDays_whenDatabaseRejectsForOtherReason_passesErrorOn() {
        DataIntegrityViolationException unknownTenant = new DataIntegrityViolationException(
                "violates foreign key constraint \"working_hours_tenant_id_fkey\"");
        when(repository.saveAllAndFlush(any())).thenThrow(unknownTenant);

        assertThatThrownBy(() -> service.changeWorkingDays(TENANT_ID, List.of(MONDAY)))
                .isSameAs(unknownTenant);
    }
}

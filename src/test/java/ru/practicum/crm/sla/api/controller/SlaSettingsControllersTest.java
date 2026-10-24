package ru.practicum.crm.sla.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.sla.api.dto.HolidayRequest;
import ru.practicum.crm.sla.api.dto.SlaPolicyRequest;
import ru.practicum.crm.sla.api.dto.SlaPolicyResponse;
import ru.practicum.crm.sla.api.dto.WorkingWeekDto;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.service.HolidayService;
import ru.practicum.crm.sla.service.SlaPolicyService;
import ru.practicum.crm.sla.service.WorkingCalendarService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Решения контроллеров настроек сроков без базы и HTTP: какую операцию сервиса вызвать по телу
 * запроса. Права, изоляцию и ответы по HTTP проверяет {@code SlaSettingsApiIntegrationTest}.
 */
class SlaSettingsControllersTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TYPE_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2027, 1, 7);

    private final TenantContext tenantContext = mock(TenantContext.class);
    private final SlaPolicyService policies = mock(SlaPolicyService.class);
    private final HolidayService holidays = mock(HolidayService.class);
    private final WorkingCalendarService calendars = mock(WorkingCalendarService.class);

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
    }

    /** Ни типа, ни приоритета — политика по умолчанию. */
    @Test
    void createPolicy_withoutTypeAndPriority_createsDefaultPolicy() {
        SlaTerms terms = new SlaTerms(240, 960);
        when(policies.createDefault(TENANT_ID, terms))
                .thenReturn(SlaPolicy.byDefault(TENANT_ID, terms));

        SlaPolicyResponse response = new SlaPolicyController(policies, tenantContext)
                .create(new SlaPolicyRequest(null, null, 240, 960));

        assertThat(response.defaultPolicy()).isTrue();
        verify(policies, never()).create(any(), any(), any(), any());
    }

    /**
     * Задан хотя бы один из двух — политика для пары: недостающее поле сервис назовёт в ошибке, а
     * не превратит запрос в политику по умолчанию.
     */
    @Test
    void createPolicy_withTypeOnly_asksServiceForPairPolicy() {
        SlaTerms terms = new SlaTerms(60, 240);
        when(policies.create(TENANT_ID, TYPE_ID, null, terms))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, null,
                        List.of(ValidationError.ofField("priority", "не должно быть пустым"))));

        ApiException thrown = catchThrowableOfType(
                () -> new SlaPolicyController(policies, tenantContext)
                        .create(new SlaPolicyRequest(TYPE_ID, null, 60, 240)),
                ApiException.class);

        assertThat(thrown.getErrors()).extracting(ValidationError::pointer)
                .containsExactly("#/priority");
        verify(policies, never()).createDefault(any(), any());
    }

    @Test
    void createPolicy_withTypeAndPriority_createsPairPolicy() {
        SlaTerms terms = new SlaTerms(60, 240);
        when(policies.create(TENANT_ID, TYPE_ID, RequestPriority.URGENT, terms))
                .thenReturn(SlaPolicy.forPair(TENANT_ID, TYPE_ID, RequestPriority.URGENT, terms));

        SlaPolicyResponse response = new SlaPolicyController(policies, tenantContext)
                .create(new SlaPolicyRequest(TYPE_ID, RequestPriority.URGENT, 60, 240));

        assertThat(response.defaultPolicy()).isFalse();
        assertThat(response.priority()).isEqualTo(RequestPriority.URGENT);
    }

    @Test
    void createHoliday_markedWorking_createsWorkingDayWithHours() {
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(15, 0);
        when(holidays.createWorkingDay(TENANT_ID, DATE, "Перенос", start, end))
                .thenReturn(Holiday.workingDay(TENANT_ID, DATE, "Перенос", start, end));

        assertThat(new HolidayController(holidays, tenantContext)
                .create(new HolidayRequest(DATE, "Перенос", true, start, end)).working())
                .isTrue();
        verify(holidays, never()).createDayOff(any(), any(), any());
    }

    @Test
    void createHoliday_notMarkedWorking_createsDayOff() {
        when(holidays.createDayOff(TENANT_ID, DATE, "Рождество"))
                .thenReturn(Holiday.dayOff(TENANT_ID, DATE, "Рождество"));

        assertThat(new HolidayController(holidays, tenantContext)
                .create(new HolidayRequest(DATE, "Рождество", false, null, null)).working())
                .isFalse();
    }

    /** Часы у нерабочего дня — ошибка в каждом лишнем поле, до обращения к справочнику. */
    @Test
    void createHoliday_dayOffWithHours_isRejectedOnEachHourField() {
        ApiException thrown = catchThrowableOfType(
                () -> new HolidayController(holidays, tenantContext).create(new HolidayRequest(
                        DATE, "Рождество", false, LocalTime.of(10, 0), LocalTime.of(15, 0))),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).extracting(ValidationError::pointer)
                .containsExactly("#/start", "#/end");
        verifyNoInteractions(holidays);
    }

    /** Неделя без списка — то же, что пустая неделя: ошибку «нет рабочих дней» даёт сервис. */
    @Test
    void changeWorkingHours_withoutDays_passesEmptyWeekToService() {
        when(calendars.changeWorkingDays(eq(TENANT_ID), any())).thenReturn(List.of());

        new WorkingCalendarController(calendars, tenantContext)
                .changeWorkingDays(new WorkingWeekDto(null));

        verify(calendars).changeWorkingDays(TENANT_ID, List.of());
    }
}

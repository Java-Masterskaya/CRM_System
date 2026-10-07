package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.repository.HolidayRepository;

/**
 * Сервис справочника без базы: репозиторий — заглушка; хранение и ограничения базы проверяет
 * интеграционный тест.
 */
class HolidayServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final LocalDate NEW_YEAR = LocalDate.of(2027, 1, 1);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime TWO_PM = LocalTime.of(14, 0);

    private final HolidayRepository repository = mock(HolidayRepository.class);
    private final HolidayService service = new HolidayService(repository);

    @Test
    void createDayOff_whenValid_storesNonWorkingDate() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Holiday saved = service.createDayOff(TENANT_ID, NEW_YEAR, "Новый год");

        verify(repository).saveAndFlush(saved);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getDate()).isEqualTo(NEW_YEAR);
        assertThat(saved.getDescription()).isEqualTo("Новый год");
        assertThat(saved.isWorking()).isFalse();
    }

    @Test
    void createWorkingDay_whenValid_storesDateWithItsHours() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Holiday saved = service.createWorkingDay(TENANT_ID, SATURDAY, "Рабочая суббота",
                TEN, TWO_PM);

        assertThat(saved.isWorking()).isTrue();
        assertThat(saved.getStartTime()).isEqualTo(TEN);
        assertThat(saved.getEndTime()).isEqualTo(TWO_PM);
    }

    @Test
    void createDayOff_whenDateAlreadyInDirectory_isRejectedAndNothingSaved() {
        when(repository.existsByTenantIdAndDate(TENANT_ID, NEW_YEAR)).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.createDayOff(TENANT_ID, NEW_YEAR, "Новый год"), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    /** Два одновременных добавления одной даты: проверку проходят оба, второе ловит база. */
    @Test
    void createDayOff_whenSameDateAddedConcurrently_isRejectedAsDuplicate() {
        when(repository.saveAndFlush(any())).thenThrow(violationOf("holidays_date_unique"));

        ApiException thrown = catchThrowableOfType(
                () -> service.createDayOff(TENANT_ID, NEW_YEAR, "Новый год"), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    void createDayOff_whenDatabaseRejectsForOtherReason_passesErrorOn() {
        DataIntegrityViolationException unknownTenant = violationOf("holidays_tenant_id_fkey");
        when(repository.saveAndFlush(any())).thenThrow(unknownTenant);

        assertThatThrownBy(() -> service.createDayOff(TENANT_ID, NEW_YEAR, "Новый год"))
                .isSameAs(unknownTenant);
    }

    @Test
    void createDayOff_withoutDateAndDescription_isRejectedWithBothFieldsAndNothingRead() {
        ApiException thrown = catchThrowableOfType(
                () -> service.createDayOff(TENANT_ID, null, " "), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("date", "не должно быть пустым"),
                ValidationError.ofField("description", "не должно быть пустым"));
        verifyNoInteractions(repository);
    }

    @Test
    void createDayOff_whenDescriptionLongerThanLimit_isRejectedWithThatField() {
        ApiException thrown = catchThrowableOfType(() -> service.createDayOff(TENANT_ID,
                NEW_YEAR, "я".repeat(256)), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(ValidationError.ofField("description",
                "должно быть не длиннее 255 символов"));
        verifyNoInteractions(repository);
    }

    @Test
    void createWorkingDay_whenHoursMissing_isRejectedWithBothFields() {
        ApiException thrown = catchThrowableOfType(() -> service.createWorkingDay(TENANT_ID,
                SATURDAY, "Перенос", null, null), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("start", "не должно быть пустым"),
                ValidationError.ofField("end", "не должно быть пустым"));
        verifyNoInteractions(repository);
    }

    @Test
    void createWorkingDay_whenStartIsNotBeforeEnd_isRejectedWithEndField() {
        ApiException thrown = catchThrowableOfType(() -> service.createWorkingDay(TENANT_ID,
                SATURDAY, "Перенос", TWO_PM, TWO_PM), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("end", "должно быть позже начала"));
        verifyNoInteractions(repository);
    }

    @Test
    void delete_whenDateOfTenantExists_removesIt() {
        Holiday holiday = Holiday.dayOff(TENANT_ID, NEW_YEAR, "Новый год");
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(holiday));

        service.delete(TENANT_ID, id);

        verify(repository).delete(holiday);
    }

    @Test
    void delete_whenTenantHasNoSuchDate_isNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(() -> service.delete(TENANT_ID, id),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository, never()).delete(any());
    }

    @Test
    void holidays_whenPageLargerThanLimit_isRejectedWithoutReading() {
        ApiException thrown = catchThrowableOfType(() -> service.holidays(TENANT_ID,
                PageRequest.of(0, PageRequests.MAX_SIZE + 1)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    /**
     * Отказ базы, как его видит сервис: Spring кладёт причиной исключение Hibernate с именем
     * нарушенного ограничения.
     */
    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new SQLException("violates constraint"), constraint));
    }
}

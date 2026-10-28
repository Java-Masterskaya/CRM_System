package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.repository.RequestTypeRepository;

@ExtendWith(MockitoExtension.class)
class RequestTypeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TYPE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private RequestTypeRepository repository;

    private RequestTypeService service;

    @BeforeEach
    void setUp() {
        service = new RequestTypeService(repository);
    }

    @Test
    void create_whenNameIsFree_savesActiveType() {
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Выгрузка")).thenReturn(false);
        when(repository.saveAndFlush(any(RequestType.class)))
                .thenAnswer(call -> call.getArgument(0));

        RequestType created = service.create(TENANT_ID, "Выгрузка", "Выгрузка отчётов",
                RequestPriority.HIGH);

        assertThat(created.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(created.getName()).isEqualTo("Выгрузка");
        assertThat(created.getDescription()).isEqualTo("Выгрузка отчётов");
        assertThat(created.getDefaultPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void create_whenNameAlreadyUsedByTenant_isRejectedAndNothingSaved() {
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Выгрузка")).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.create(TENANT_ID, "Выгрузка", null, null), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * Параллельный запрос успел создать тип с тем же названием после проверки: повтор ловит
     * уникальный индекс, и ответ тот же, что у проверки, — 409, а не 500.
     */
    @Test
    void create_whenSameNameCreatedConcurrently_isRejectedAsAlreadyExists() {
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Выгрузка")).thenReturn(false);
        when(repository.saveAndFlush(any(RequestType.class)))
                .thenThrow(violationOf("request_types_name_unique"));

        ApiException thrown = catchThrowableOfType(
                () -> service.create(TENANT_ID, "Выгрузка", null, null), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(thrown.getDetail()).isEqualTo("Тип заявки с таким названием уже есть.");
    }

    /** Нарушение другого ограничения — не повтор названия: оно не маскируется под 409. */
    @Test
    void create_whenOtherConstraintViolated_rethrowsDatabaseError() {
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Выгрузка")).thenReturn(false);
        DataIntegrityViolationException violation = violationOf("request_types_tenant_id_fkey");
        when(repository.saveAndFlush(any(RequestType.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.create(TENANT_ID, "Выгрузка", null, null))
                .isSameAs(violation);
    }

    @Test
    void update_whenTypeBelongsToAnotherTenant_isRejectedAsNotFound() {
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(
                () -> service.update(TENANT_ID, TYPE_ID, "Импорт", null, null),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_whenNameUnchanged_doesNotComplainAboutDuplicate() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any(RequestType.class)))
                .thenAnswer(call -> call.getArgument(0));

        RequestType updated = service.update(TENANT_ID, TYPE_ID, "Выгрузка", "Новое пояснение",
                RequestPriority.LOW);

        assertThat(updated.getDescription()).isEqualTo("Новое пояснение");
        assertThat(updated.getDefaultPriority()).isEqualTo(RequestPriority.LOW);
        verify(repository, never()).existsByTenantIdAndNameIgnoreCase(any(), any());
    }

    @Test
    void update_whenOnlyLetterCaseChanged_renamesWithoutTreatingTypeAsItsOwnDuplicate() {
        RequestType stored = new RequestType(TENANT_ID, "выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any(RequestType.class)))
                .thenAnswer(call -> call.getArgument(0));

        RequestType updated = service.update(TENANT_ID, TYPE_ID, "Выгрузка", null, null);

        assertThat(updated.getName()).isEqualTo("Выгрузка");
        verify(repository, never()).existsByTenantIdAndNameIgnoreCase(any(), any());
    }

    @Test
    void update_whenNewNameTakenByAnotherType_isRejected() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Импорт")).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.update(TENANT_ID, TYPE_ID, "Импорт", null, null),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(stored.getName()).isEqualTo("Выгрузка");
        verify(repository, never()).saveAndFlush(any());
    }

    /** Переименование, которое опередил параллельный запрос, — тоже 409, а не 500. */
    @Test
    void update_whenNewNameTakenConcurrently_isRejectedAsAlreadyExists() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.existsByTenantIdAndNameIgnoreCase(TENANT_ID, "Импорт")).thenReturn(false);
        when(repository.saveAndFlush(any(RequestType.class)))
                .thenThrow(violationOf("request_types_name_unique"));

        ApiException thrown = catchThrowableOfType(
                () -> service.update(TENANT_ID, TYPE_ID, "Импорт", null, null),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    void deactivate_whenTypeExists_turnsItOffWithoutDeleting() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(RequestType.class))).thenAnswer(call -> call.getArgument(0));

        RequestType result = service.deactivate(TENANT_ID, TYPE_ID);

        assertThat(result.isActive()).isFalse();
    }

    @Test
    void activate_whenTypeWasDisabled_turnsItBackOn() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        stored.deactivate();
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(RequestType.class))).thenAnswer(call -> call.getArgument(0));

        RequestType result = service.activate(TENANT_ID, TYPE_ID);

        assertThat(result.isActive()).isTrue();
    }

    /**
     * Список действующих типов — только у арендатора и только действующие; страница — из запроса,
     * порядок — всегда по названию, затем по {@code id}.
     */
    @Test
    void activeTypes_whenCalled_asksRepositoryForActiveTypesOfTenantByName() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByTenantIdAndActiveTrue(eq(TENANT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stored)));

        Page<RequestType> page = service.activeTypes(TENANT_ID, PageRequest.of(2, 30));

        assertThat(page.getContent()).containsExactly(stored);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByTenantIdAndActiveTrue(eq(TENANT_ID), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(30);
        assertThat(pageable.getValue().getSort())
                .containsExactly(Sort.Order.asc("name"), Sort.Order.asc("id"));
    }

    @Test
    void types_whenCalled_returnsDisabledTypesToo() {
        RequestType disabled = new RequestType(TENANT_ID, "Старый тип");
        disabled.deactivate();
        when(repository.findByTenantId(eq(TENANT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(disabled)));

        assertThat(service.types(TENANT_ID, PageRequest.of(0, 20)).getContent())
                .containsExactly(disabled);
    }

    /** Страница больше допустимой не уходит в базу, даже если её передали в обход API. */
    @Test
    void types_whenPageIsTooLarge_isRejectedBeforeQuery() {
        assertThatThrownBy(() -> service.types(TENANT_ID,
                PageRequest.of(0, PageRequests.MAX_SIZE + 1)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void requireActive_whenTypeIsActive_returnsIt() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));

        assertThat(service.requireActive(TENANT_ID, TYPE_ID)).isSameAs(stored);
    }

    @Test
    void requireActive_whenTypeIsDisabled_isRejectedWithFieldError() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        stored.deactivate();
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));

        ApiException thrown = catchThrowableOfType(
                () -> service.requireActive(TENANT_ID, TYPE_ID), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.pointer()).isEqualTo("#/typeId");
                    assertThat(error.detail()).isEqualTo("тип заявки отключён");
                });
    }

    /** Отказ базы в том виде, в каком его передаёт Spring: имя ограничения — в причине. */
    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new SQLException("violates constraint"), constraint));
    }
}

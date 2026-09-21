package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
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
        when(repository.existsByTenantIdAndName(TENANT_ID, "Выгрузка")).thenReturn(false);
        when(repository.save(any(RequestType.class))).thenAnswer(call -> call.getArgument(0));

        RequestType created = service.create(TENANT_ID, "Выгрузка", "Выгрузка отчётов", "HIGH");

        assertThat(created.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(created.getName()).isEqualTo("Выгрузка");
        assertThat(created.getDescription()).isEqualTo("Выгрузка отчётов");
        assertThat(created.getDefaultPriority()).isEqualTo("HIGH");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void create_whenNameAlreadyUsedByTenant_isRejectedAndNothingSaved() {
        when(repository.existsByTenantIdAndName(TENANT_ID, "Выгрузка")).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.create(TENANT_ID, "Выгрузка", null, null), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).save(any());
    }

    @Test
    void update_whenTypeBelongsToAnotherTenant_isRejectedAsNotFound() {
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(
                () -> service.update(TENANT_ID, TYPE_ID, "Импорт", null, null),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(repository, never()).save(any());
    }

    @Test
    void update_whenNameUnchanged_doesNotComplainAboutDuplicate() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(RequestType.class))).thenAnswer(call -> call.getArgument(0));

        RequestType updated = service.update(TENANT_ID, TYPE_ID, "Выгрузка", "Новое пояснение",
                "LOW");

        assertThat(updated.getDescription()).isEqualTo("Новое пояснение");
        assertThat(updated.getDefaultPriority()).isEqualTo("LOW");
        verify(repository, never()).existsByTenantIdAndName(any(), any());
    }

    @Test
    void update_whenNewNameTakenByAnotherType_isRejected() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByIdAndTenantId(TYPE_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(repository.existsByTenantIdAndName(TENANT_ID, "Импорт")).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.update(TENANT_ID, TYPE_ID, "Импорт", null, null),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(stored.getName()).isEqualTo("Выгрузка");
        verify(repository, never()).save(any());
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

    @Test
    void listActive_whenCalled_asksRepositoryOnlyForActiveTypesOfTenant() {
        RequestType stored = new RequestType(TENANT_ID, "Выгрузка");
        when(repository.findByTenantIdAndActiveTrueOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(stored));

        assertThat(service.listActive(TENANT_ID)).containsExactly(stored);
    }

    @Test
    void listAll_whenCalled_returnsDisabledTypesToo() {
        RequestType disabled = new RequestType(TENANT_ID, "Старый тип");
        disabled.deactivate();
        when(repository.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of(disabled));

        assertThat(service.listAll(TENANT_ID)).containsExactly(disabled);
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
}

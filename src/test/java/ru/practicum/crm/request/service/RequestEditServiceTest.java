
package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.EditRequestRequest;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

class RequestEditServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID OLD_TYPE_ID = UUID.randomUUID();
    private static final UUID NEW_TYPE_ID = UUID.randomUUID();
    private static final long VERSION = 3L;

    private final RequestRepository requests = mock(RequestRepository.class);
    private final RequestTypeService requestTypes = mock(RequestTypeService.class);
    private final RequestAuditService audit = mock(RequestAuditService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TenantContext tenantContext = mock(TenantContext.class);

    private final RequestEditService service = new RequestEditService(
            requests, requestTypes, audit, currentUser, tenantContext);

    private Request request;

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(currentUser.id()).thenReturn(OPERATOR_ID);

        // Имитируем увеличение @Version при сохранении Hibernate.
        when(requests.saveAndFlush(any())).thenAnswer(invocation -> {
            Request saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(
                    saved, "version", saved.getVersion() + 1);
            return saved;
        });

        request = new Request(
                TENANT_ID,
                AUTHOR_ID,
                "Выгрузка за квартал",
                "Старое описание",
                RequestStatus.IN_PROGRESS);

        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        ReflectionTestUtils.setField(request, "version", VERSION);

        request.setPriority(RequestPriority.values()[0]);
        request.setTypeId(OLD_TYPE_ID);
        request.setDesiredDueAt(Instant.parse("2026-11-01T12:00:00Z"));

        when(requests.findByIdAndTenantIdAndDeletedFalse(
                REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));
    }

    @Test
    void edit_validCommand_updatesEditableFieldsAndReturnsCard() {
        RequestPriority newPriority = differentPriority(
                request.getPriority());
        Instant newDueAt = Instant.parse("2026-12-01T12:00:00Z");

        EditRequestRequest command = command(
                newPriority, NEW_TYPE_ID, newDueAt, "Новое описание", VERSION);

        RequestCardResponse response = service.edit(REQUEST_ID, command);

        assertThat(request.getPriority()).isEqualTo(newPriority);
        assertThat(request.getTypeId()).isEqualTo(NEW_TYPE_ID);
        assertThat(request.getDesiredDueAt()).isEqualTo(newDueAt);
        assertThat(request.getDescription()).isEqualTo("Новое описание");

        // Редактирование не должно менять статус, автора и арендатора.
        assertThat(request.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(request.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(request.getTenantId()).isEqualTo(TENANT_ID);

        assertThat(response.id()).isEqualTo(REQUEST_ID);
        assertThat(response.priority()).isEqualTo(newPriority);
        assertThat(response.typeId()).isEqualTo(NEW_TYPE_ID);
        assertThat(response.description()).isEqualTo("Новое описание");
        assertThat(response.status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(response.version()).isEqualTo(VERSION + 1);

        verify(requestTypes).requireActive(TENANT_ID, NEW_TYPE_ID);
        verify(requests).saveAndFlush(request);
        verify(audit).record(eq(request), any(RequestSnapshot.class),
                eq(OPERATOR_ID));
    }

    @Test
    void edit_withStaleVersion_throwsConflictAndDoesNotSave() {
        EditRequestRequest command = command(
                differentPriority(request.getPriority()),
                NEW_TYPE_ID,
                Instant.parse("2026-12-01T12:00:00Z"),
                "Не должно сохраниться",
                VERSION - 1);

        assertThatThrownBy(() -> service.edit(REQUEST_ID, command))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(request.getPriority()).isEqualTo(RequestPriority.values()[0]);
        assertThat(request.getTypeId()).isEqualTo(OLD_TYPE_ID);
        assertThat(request.getDescription()).isEqualTo("Старое описание");

        verify(requests, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void edit_whenRequestBelongsToAnotherTenant_throwsNotFound() {
        when(requests.findByIdAndTenantIdAndDeletedFalse(
                REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.edit(
                REQUEST_ID,
                command(RequestPriority.values()[0], NEW_TYPE_ID,
                        null, "Описание", VERSION)))
                .isInstanceOfSatisfying(
                        ru.practicum.crm.common.error.NotFoundException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));

        verify(requests, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void edit_withForbiddenStatusProperty_rejectsRequestBeforeReadingRequest() {
        EditRequestRequest command = command(
                RequestPriority.values()[0], NEW_TYPE_ID,
                null, "Описание", VERSION);
        command.captureUnknownProperty("status", "DONE");

        assertThatThrownBy(() -> service.edit(REQUEST_ID, command))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getErrors())
                            .extracting(error -> error.pointer())
                            .contains("#/status");
                });

        verifyNoInteractions(
                requests, requestTypes, audit, currentUser, tenantContext);
    }

    @Test
    void edit_whenDesiredDueAtIsOmitted_preservesExistingDeadline() {
        EditRequestRequest command = new EditRequestRequest();
        command.setVersion(VERSION);
        command.setPriority(request.getPriority());
        command.setTypeId(NEW_TYPE_ID);
        command.setDescription("Новое описание");
        // setDesiredDueAt намеренно не вызываем.

        Instant originalDueAt = request.getDesiredDueAt();

        service.edit(REQUEST_ID, command);

        assertThat(request.getDesiredDueAt()).isEqualTo(originalDueAt);
        verify(requests).saveAndFlush(request);
    }

    @Test
    void edit_whenDesiredDueAtIsExplicitlyNull_clearsDeadline() {
        EditRequestRequest command = command(
                request.getPriority(),
                NEW_TYPE_ID,
                null,
                "Новое описание",
                VERSION);

        service.edit(REQUEST_ID, command);

        assertThat(request.getDesiredDueAt()).isNull();
        verify(requests).saveAndFlush(request);
    }

    @Test
    void edit_whenRequestIsTerminal_throwsRequestClosedAndDoesNotSave() {
        ReflectionTestUtils.setField(request, "status", RequestStatus.DONE);

        EditRequestRequest command = command(
                differentPriority(request.getPriority()),
                NEW_TYPE_ID,
                Instant.parse("2026-12-01T12:00:00Z"),
                "Не должно сохраниться",
                VERSION);

        assertThatThrownBy(() -> service.edit(REQUEST_ID, command))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.REQUEST_CLOSED));

        assertThat(request.getPriority()).isEqualTo(RequestPriority.values()[0]);
        assertThat(request.getTypeId()).isEqualTo(OLD_TYPE_ID);
        assertThat(request.getDescription()).isEqualTo("Старое описание");

        verify(requests, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void edit_recordsSnapshotBeforeChangingFields() {
        EditRequestRequest command = command(
                differentPriority(request.getPriority()),
                NEW_TYPE_ID,
                Instant.parse("2026-12-01T12:00:00Z"),
                "Новое описание",
                VERSION);

        ArgumentCaptor<RequestSnapshot> captor =
                ArgumentCaptor.forClass(RequestSnapshot.class);

        RequestSnapshot expectedBefore = RequestSnapshot.of(request);

        service.edit(REQUEST_ID, command);

        verify(audit).record(eq(request), captor.capture(), eq(OPERATOR_ID));

        assertThat(captor.getValue()).isEqualTo(expectedBefore);
    }

    @Test
    void edit_whenDescriptionChanges_recordsAuditAfterSaving() {
        EditRequestRequest command = command(
                request.getPriority(), OLD_TYPE_ID,
                request.getDesiredDueAt(), "Изменённое описание", VERSION);

        service.edit(REQUEST_ID, command);

        org.mockito.InOrder order =
                org.mockito.Mockito.inOrder(requests, audit);
        order.verify(requests).saveAndFlush(request);
        order.verify(audit).record(
                eq(request), any(RequestSnapshot.class), eq(OPERATOR_ID));
    }

    private static EditRequestRequest command(
            RequestPriority priority,
            UUID typeId,
            Instant desiredDueAt,
            String description,
            long version) {

        EditRequestRequest command = new EditRequestRequest();
        command.setVersion(version);
        command.setPriority(priority);
        command.setTypeId(typeId);
        command.setDesiredDueAt(desiredDueAt);
        command.setDescription(description);
        return command;
    }

    private static RequestPriority differentPriority(RequestPriority current) {
        for (RequestPriority candidate : RequestPriority.values()) {
            if (candidate != current) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Для теста необходимы как минимум два значения RequestPriority");
    }
}

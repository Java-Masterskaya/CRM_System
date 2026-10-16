package ru.practicum.crm.request.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.RequestAuditService;

class RequestAssignmentServiceImplTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final RequestRepository repository = mock(RequestRepository.class);
    private final RequestAuditService auditService = mock(RequestAuditService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RequestAssignmentServiceImpl service =
            new RequestAssignmentServiceImpl(repository, auditService, clock);

    @Test
    void changeAssignee_whenRequestMissingForTenant_throwsNotFound() {
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeAssignee(TENANT_ID, REQUEST_ID,
                UUID.randomUUID(), 0, ACTOR_ID))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> assertThat(((NotFoundException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void changeAssignee_whenVersionStale_throwsOptimisticLockFailureWithoutChangingRequest() {
        Request request = savedRequest(RequestStatus.NEW);
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.changeAssignee(TENANT_ID, REQUEST_ID,
                UUID.randomUUID(), request.getVersion() + 1, ACTOR_ID))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(request.getAssigneeId()).isNull();
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void changeAssignee_whenRequestInTerminalStatus_isRejectedAsClosed() {
        Request request = savedRequest(RequestStatus.DONE);
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        ApiException thrown = catchThrowableOfType(
                () -> service.changeAssignee(TENANT_ID, REQUEST_ID, UUID.randomUUID(), 0,
                        ACTOR_ID), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.REQUEST_CLOSED);
        assertThat(request.getAssigneeId()).isNull();
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void changeAssignee_whenNewAssignee_mutatesRequestRecordsAuditAndReturnsDto() {
        Request request = savedRequest(RequestStatus.IN_PROGRESS);
        UUID assigneeId = UUID.randomUUID();
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        RequestAssignmentDto result = service.changeAssignee(TENANT_ID, REQUEST_ID, assigneeId,
                0, ACTOR_ID);

        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.authorId()).isEqualTo(AUTHOR_ID);
        assertThat(result.subject()).isEqualTo("Выгрузка");
        assertThat(result.assigneeId()).isEqualTo(assigneeId);
        assertThat(result.assignedAt()).isEqualTo(NOW);
        assertThat(request.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(request.getAssignedAt()).isEqualTo(NOW);
        verify(auditService).record(eq(request), any(), eq(ACTOR_ID));
    }

    @Test
    void changeAssignee_whenSameAssigneeAsCurrent_isNoOpAndSkipsAudit() {
        UUID assigneeId = UUID.randomUUID();
        Request request = savedRequest(RequestStatus.IN_PROGRESS);
        request.changeAssignee(assigneeId, NOW.minusSeconds(60));
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        RequestAssignmentDto result = service.changeAssignee(TENANT_ID, REQUEST_ID, assigneeId,
                0, ACTOR_ID);

        assertThat(result.assignedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void changeAssignee_whenNull_unassignsAndRecordsAudit() {
        Request request = savedRequest(RequestStatus.IN_PROGRESS);
        request.changeAssignee(UUID.randomUUID(), NOW.minusSeconds(60));
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        RequestAssignmentDto result = service.changeAssignee(TENANT_ID, REQUEST_ID, null, 0,
                ACTOR_ID);

        assertThat(result.assigneeId()).isNull();
        assertThat(result.assignedAt()).isNull();
        verify(auditService).record(eq(request), any(), eq(ACTOR_ID));
    }

    private static Request savedRequest(RequestStatus status) {
        Request request = new Request(TENANT_ID, AUTHOR_ID, "Выгрузка", "Нужен отчёт", status);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        return request;
    }
}

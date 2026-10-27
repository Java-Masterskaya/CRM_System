package ru.practicum.crm.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.api.RequestAssignmentService;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.user.api.PermissionCode;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.ActiveUserDto;
import ru.practicum.crm.user.api.dto.UserContactDto;

class AssignmentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID ASSIGNEE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final RequestAssignmentService requestAssignmentService =
            mock(RequestAssignmentService.class);
    private final UserService userService = mock(UserService.class);
    private final UserPermissionService userPermissionService =
            mock(UserPermissionService.class);
    private final OutboxPublisher outboxPublisher = mock(OutboxPublisher.class);
    private final AssignmentService service = new AssignmentService(requestAssignmentService,
            userService, userPermissionService, outboxPublisher);

    @Test
    void assign_whenAssigneeNotFoundInTenant_throwsNotFoundWithoutTouchingRequest() {
        when(userService.findActiveInTenant(ASSIGNEE_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        NotFoundException thrown = catchThrowableOfType(
                () -> service.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID),
                NotFoundException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(requestAssignmentService, never()).changeAssignee(any(), any(), any(), anyLong(),
                any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }

    @Test
    void assign_whenAssigneeLacksRightToLeadRequest_throwsAccessDeniedWithoutTouchingRequest() {
        when(userService.findActiveInTenant(ASSIGNEE_ID, TENANT_ID))
                .thenReturn(Optional.of(new ActiveUserDto(ASSIGNEE_ID, "assignee@example.com")));
        when(userPermissionService.hasPermission(ASSIGNEE_ID,
                PermissionCode.REQUEST_STATUS_CHANGE)).thenReturn(false);

        ApiException thrown = catchThrowableOfType(
                () -> service.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        verify(requestAssignmentService, never()).changeAssignee(any(), any(), any(), anyLong(),
                any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }

    @Test
    void assign_whenAssigneeValid_delegatesToRequestPackageAndNotifiesAuthor() {
        when(userService.findActiveInTenant(ASSIGNEE_ID, TENANT_ID))
                .thenReturn(Optional.of(new ActiveUserDto(ASSIGNEE_ID, "assignee@example.com")));
        when(userPermissionService.hasPermission(ASSIGNEE_ID,
                PermissionCode.REQUEST_STATUS_CHANGE)).thenReturn(true);
        RequestAssignmentDto assignmentResult = new RequestAssignmentDto(REQUEST_ID, AUTHOR_ID,
                "Выгрузка отчёта", ASSIGNEE_ID, Instant.parse("2026-10-13T10:00:00Z"), 1);
        when(requestAssignmentService.changeAssignee(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0,
                ACTOR_ID)).thenReturn(assignmentResult);
        when(userService.findActiveInTenant(AUTHOR_ID, TENANT_ID))
                .thenReturn(Optional.of(new ActiveUserDto(AUTHOR_ID, "author@example.com")));

        RequestAssignmentDto result = service.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0,
                ACTOR_ID);

        assertThat(result).isEqualTo(assignmentResult);
        verify(outboxPublisher).publish(eq(TENANT_ID), eq(EmailNotification.EVENT_TYPE),
                eq("REQUEST"), eq(REQUEST_ID), anyMap());
    }

    @Test
    void assign_whenAuthorNoLongerActive_stillAssignsButSkipsNotification() {
        when(userService.findActiveInTenant(ASSIGNEE_ID, TENANT_ID))
                .thenReturn(Optional.of(new ActiveUserDto(ASSIGNEE_ID, "assignee@example.com")));
        when(userPermissionService.hasPermission(ASSIGNEE_ID,
                PermissionCode.REQUEST_STATUS_CHANGE)).thenReturn(true);
        RequestAssignmentDto assignmentResult = new RequestAssignmentDto(REQUEST_ID, AUTHOR_ID,
                "Выгрузка отчёта", ASSIGNEE_ID, Instant.parse("2026-10-13T10:00:00Z"), 1);
        when(requestAssignmentService.changeAssignee(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0,
                ACTOR_ID)).thenReturn(assignmentResult);
        when(userService.findActiveInTenant(AUTHOR_ID, TENANT_ID)).thenReturn(Optional.empty());

        RequestAssignmentDto result = service.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0,
                ACTOR_ID);

        assertThat(result).isEqualTo(assignmentResult);
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }

    @Test
    void assign_whenAssigneeIdNull_unassignsWithoutCandidateChecksOrNotification() {
        RequestAssignmentDto assignmentResult = new RequestAssignmentDto(REQUEST_ID, AUTHOR_ID,
                "Выгрузка отчёта", null, null, 1);
        when(requestAssignmentService.changeAssignee(TENANT_ID, REQUEST_ID, null, 0, ACTOR_ID))
                .thenReturn(assignmentResult);

        RequestAssignmentDto result = service.assign(TENANT_ID, REQUEST_ID, null, 0, ACTOR_ID);

        assertThat(result).isEqualTo(assignmentResult);
        verify(userService, never()).findActiveInTenant(any(), any());
        verify(userPermissionService, never()).hasPermission(any(), any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }

    @Test
    void autoAssign_whenNoEligibleCandidates_throwsExplainedConflictWithoutAssigning() {
        when(userService.findActiveWithPermission(TENANT_ID,
                PermissionCode.REQUEST_STATUS_CHANGE.name())).thenReturn(List.of());
        when(requestAssignmentService.activeWorkloadByAssignee(TENANT_ID)).thenReturn(Map.of());

        ApiException thrown = catchThrowableOfType(
                () -> service.autoAssign(TENANT_ID, REQUEST_ID, 0, ACTOR_ID), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NO_ELIGIBLE_ASSIGNEE);
        assertThat(thrown.getDetail()).contains("правом вести заявки");
        verify(requestAssignmentService, never()).changeAssignee(any(), any(), any(), anyLong(),
                any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }

    @Test
    void autoAssign_whenCandidateLoadsDiffer_assignsLeastLoadedAndNotifiesAuthor() {
        UserContactDto loaded = new UserContactDto(UUID.randomUUID(), "loaded@example.com", null);
        UserContactDto free = new UserContactDto(UUID.randomUUID(), "free@example.com", null);
        when(userService.findActiveWithPermission(TENANT_ID,
                PermissionCode.REQUEST_STATUS_CHANGE.name())).thenReturn(List.of(loaded, free));
        when(requestAssignmentService.activeWorkloadByAssignee(TENANT_ID))
                .thenReturn(Map.of(loaded.id(), 4L, free.id(), 1L));
        RequestAssignmentDto assignmentResult = new RequestAssignmentDto(REQUEST_ID, AUTHOR_ID,
                "Выгрузка отчёта", free.id(), Instant.parse("2026-10-13T10:00:00Z"), 1);
        when(requestAssignmentService.changeAssignee(TENANT_ID, REQUEST_ID, free.id(), 0,
                ACTOR_ID)).thenReturn(assignmentResult);
        when(userService.findActiveInTenant(AUTHOR_ID, TENANT_ID))
                .thenReturn(Optional.of(new ActiveUserDto(AUTHOR_ID, "author@example.com")));

        RequestAssignmentDto result = service.autoAssign(TENANT_ID, REQUEST_ID, 0, ACTOR_ID);

        assertThat(result).isEqualTo(assignmentResult);
        verify(requestAssignmentService).activeWorkloadByAssignee(TENANT_ID);
        verify(outboxPublisher).publish(eq(TENANT_ID), eq(EmailNotification.EVENT_TYPE),
                eq("REQUEST"), eq(REQUEST_ID), anyMap());
    }

    @Test
    void autoAssign_whenRequestCannotBeAssigned_propagatesErrorAndSkipsNotification() {
        UserContactDto free = new UserContactDto(UUID.randomUUID(), "free@example.com", null);
        when(userService.findActiveWithPermission(TENANT_ID,
                PermissionCode.REQUEST_STATUS_CHANGE.name())).thenReturn(List.of(free));
        when(requestAssignmentService.activeWorkloadByAssignee(TENANT_ID)).thenReturn(Map.of());
        when(requestAssignmentService.changeAssignee(TENANT_ID, REQUEST_ID, free.id(), 0, ACTOR_ID))
                .thenThrow(new ApiException(ErrorCode.REQUEST_CLOSED, "Заявка закрыта"));

        ApiException thrown = catchThrowableOfType(
                () -> service.autoAssign(TENANT_ID, REQUEST_ID, 0, ACTOR_ID), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.REQUEST_CLOSED);
        verify(outboxPublisher, never()).publish(any(), any(), any(), any(), anyMap());
    }
}

package ru.practicum.crm.assignment.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.api.RequestAssignmentService;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;
import ru.practicum.crm.user.api.PermissionCode;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.ActiveUserDto;

@Service
public class AssignmentService {

    private final RequestAssignmentService requestAssignmentService;
    private final UserService userService;
    private final UserPermissionService userPermissionService;
    private final OutboxPublisher outboxPublisher;

    public AssignmentService(RequestAssignmentService requestAssignmentService,
                             UserService userService, UserPermissionService userPermissionService,
                             OutboxPublisher outboxPublisher) {
        this.requestAssignmentService = requestAssignmentService;
        this.userService = userService;
        this.userPermissionService = userPermissionService;
        this.outboxPublisher = outboxPublisher;
    }

    @Transactional
    public RequestAssignmentDto assign(UUID tenantId, UUID requestId, UUID assigneeId,
                                       long expectedVersion, UUID actorId) {
        String message = "У кандидата нет права вести заявку";
        ActiveUserDto candidate = assigneeId == null ? null
                : requireCandidateEligible(tenantId, assigneeId, message);

        RequestAssignmentDto result = requestAssignmentService.changeAssignee(tenantId, requestId,
                assigneeId, expectedVersion, actorId);

        if (candidate != null) {
            notifyAuthor(tenantId, result, candidate);
        }

        return result;
    }

    @Transactional
    public RequestAssignmentDto take(UUID tenantId, UUID requestId, UUID actorId) {
        String message = "У вас нет права назначать себя исполнителем заявки";
        ActiveUserDto candidate = requireCandidateEligible(tenantId, actorId, message);

        RequestAssignmentOutcome outcome =
                requestAssignmentService.takeUnassigned(tenantId, requestId, actorId);

        if (outcome.changed()) {
            notifyAuthor(tenantId, outcome.assignment(), candidate);
        }

        return outcome.assignment();
    }

    private ActiveUserDto requireCandidateEligible(
            UUID tenantId,
            UUID assigneeId,
            String accessDeniedMessage) {
        ActiveUserDto candidate = userService.findActiveInTenant(assigneeId, tenantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Пользователь не найден: id = " + assigneeId));

        if (!userPermissionService.hasPermission(assigneeId,
                PermissionCode.REQUEST_STATUS_CHANGE)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    accessDeniedMessage);
        }
        return candidate;
    }

    private void notifyAuthor(UUID tenantId, RequestAssignmentDto result,
                              ActiveUserDto assignee) {
        userService.findActiveInTenant(result.authorId(), tenantId).ifPresent(author -> {
            EmailNotification notification = new EmailNotification(author.email(),
                    NotificationType.REQUEST_ASSIGNED, variables(author, assignee, result));
            outboxPublisher.publish(tenantId, EmailNotification.EVENT_TYPE, "REQUEST",
                    result.requestId(), notification.toPayload());
        });
    }

    private static Map<String, String> variables(ActiveUserDto author, ActiveUserDto assignee,
                                                 RequestAssignmentDto result) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("recipientName", author.email());
        variables.put("requestId", result.requestId().toString());
        variables.put("requestSubject", result.subject());
        variables.put("assigneeName", assignee.email());
        return variables;
    }
}

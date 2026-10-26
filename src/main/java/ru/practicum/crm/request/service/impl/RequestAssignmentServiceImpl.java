package ru.practicum.crm.request.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.RequestAssignmentService;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.RequestAuditService;

@Service
public class RequestAssignmentServiceImpl implements RequestAssignmentService {

    private final RequestRepository repository;
    private final RequestAuditService auditService;
    private final Clock clock;

    public RequestAssignmentServiceImpl(RequestRepository repository,
                                        RequestAuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RequestAssignmentDto changeAssignee(UUID tenantId, UUID requestId, UUID assigneeId,
                                               long expectedVersion, UUID actorId) {
        Request request = repository.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Заявка не найдена: id = " + requestId));

        request.requireVersion(expectedVersion);

        if (request.getStatus().isTerminal()) {
            throw new ApiException(ErrorCode.REQUEST_CLOSED,
                    "Заявка в статусе " + request.getStatus() + " закрыта для назначения");
        }

        RequestSnapshot before = RequestSnapshot.of(request);
        if (request.changeAssignee(assigneeId, Instant.now(clock))) {
            auditService.record(request, before, actorId);
        }

        return toDto(request);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RequestAssignmentOutcome takeUnassigned(UUID tenantId, UUID requestId, UUID actorId) {
        Instant assignedAt = Instant.now(clock);

        int updated = repository.takeIfUnassigned(tenantId, requestId, actorId, assignedAt);

        Request request = repository
                .findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.NOT_FOUND,
                        "Заявка не найдена: id = " + requestId
                ));

        if (updated == 1) {
            RequestSnapshot before = new RequestSnapshot(
                    request.getPriority(),
                    request.getTypeId(),
                    request.getDesiredDueAt(),
                    request.getDescription(),
                    null,
                    request.getFirstResponseDueAt(),
                    request.getResolutionDueAt()
            );

            auditService.record(request, before, actorId);

            return new RequestAssignmentOutcome(toDto(request), true);
        }

        if (request.getAssigneeId() != null) {
            if (request.getAssigneeId().equals(actorId)) {
                return new RequestAssignmentOutcome(toDto(request), false);
            }

            throw new ApiException(
                    ErrorCode.REQUEST_ALREADY_ASSIGNED,
                    "Заявка уже назначена исполнителю: id = "
                    + request.getAssigneeId()
            );
        }

        if (request.getStatus().isTerminal()) {
            throw new ApiException(
                    ErrorCode.REQUEST_CLOSED,
                    "Заявка в статусе " + request.getStatus()
                    + " закрыта для назначения"
            );
        }

        throw new ApiException(
                ErrorCode.STALE_VERSION,
                "Не удалось захватить заявку: её состояние изменилось. "
                + "Обновите данные и повторите попытку."
        );
    }

    private static RequestAssignmentDto toDto(Request request) {
        return new RequestAssignmentDto(
                request.getId(),
                request.getAuthorId(),
                request.getSubject(),
                request.getAssigneeId(),
                request.getAssignedAt(),
                request.getVersion()
        );
    }
}

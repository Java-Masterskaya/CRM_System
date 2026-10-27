package ru.practicum.crm.request.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.AssigneeWorkload;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.RequestAuditService;

@Service
public class RequestAssignmentServiceImpl implements RequestAssignmentService {

    /** Статусы, в которых заявка ещё не завершена и потому считается нагрузкой исполнителя. */
    private static final List<RequestStatus> OPEN_STATUSES = Arrays.stream(RequestStatus.values())
            .filter(status -> !status.isTerminal())
            .toList();

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
    public RequestAssignmentOutcome changeAssignee(UUID tenantId, UUID requestId, UUID assigneeId,
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
        boolean changed = request.changeAssignee(assigneeId, Instant.now(clock));
        if (changed) {
            auditService.record(request, before, actorId);
        }

        return new RequestAssignmentOutcome(toDto(request), changed);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireUnassigned(UUID tenantId, UUID requestId, long expectedVersion) {
        Request request = repository.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Заявка не найдена: id = " + requestId));

        request.requireVersion(expectedVersion);

        if (request.getAssigneeId() != null) {
            throw new ApiException(ErrorCode.REQUEST_ALREADY_ASSIGNED,
                    "Заявка уже назначена исполнителю: id = " + request.getAssigneeId()
                            + ". Автоподбор не переназначает исполнителя — используйте ручное"
                            + " назначение.");
        }
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
                    null
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

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> activeWorkloadByAssignee(UUID tenantId) {
        return repository.findWorkloadByAssignee(tenantId, OPEN_STATUSES).stream()
                .collect(Collectors.toMap(AssigneeWorkload::getAssigneeId,
                        AssigneeWorkload::getActiveRequests));
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

package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.common.idempotency.IdempotencyService;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreateRequest;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreatedResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserContext;

@Service
public class ClientRequestService {

    private static final String OPERATION = "CLIENT_REQUEST_CREATE";

    private final RequestRepository requestRepository;
    private final RequestTypeService requestTypeService;
    private final RequestDeadlineAssigner deadlineAssigner;
    private final TenantContext tenantContext;
    private final UserContext userContext;
    private final IdempotencyService idempotencyService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "TenantContext is an injected Spring-managed request-context"
                    + " collaborator; retaining this dependency is intentional"
    )
    public ClientRequestService(
            RequestRepository requestRepository,
            RequestTypeService requestTypeService,
            RequestDeadlineAssigner deadlineAssigner,
            TenantContext tenantContext,
            UserContext userContext,
            IdempotencyService idempotencyService
    ) {
        this.requestRepository = requestRepository;
        this.requestTypeService = requestTypeService;
        this.deadlineAssigner = deadlineAssigner;
        this.tenantContext = tenantContext;
        this.userContext = userContext;
        this.idempotencyService = idempotencyService;
    }

    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    @Transactional
    public IdempotencyResult<ClientRequestCreatedResponse> create(
            ClientRequestCreateRequest request,
            String idempotencyKey
    ) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        UUID authorId = userContext.getCurrentUserId();

        if (tenantId == null) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    ErrorCode.UNAUTHENTICATED.getDefaultDetail()
            );
        }

        return idempotencyService.execute(
                tenantId,
                authorId,
                OPERATION,
                idempotencyKey,
                request,
                HttpStatus.CREATED.value(),
                ClientRequestCreatedResponse.class,
                () -> createNewRequest(request, tenantId, authorId)
        );
    }

    private ClientRequestCreatedResponse createNewRequest(
            ClientRequestCreateRequest request,
            UUID tenantId,
            UUID authorId
    ) {
        RequestType type = requestTypeService.requireActive(
                tenantId,
                request.typeId()
        );

        Request entity = new Request(
                tenantId,
                authorId,
                request.subject(),
                request.description(),
                RequestStatus.NEW
        );

        entity.setTypeId(type.getId());
        entity.setDataParams(request.dataParams());
        entity.setPriority(type.getDefaultPriority() == null
                ? ru.practicum.crm.common.model.RequestPriority.NORMAL
                : type.getDefaultPriority());
        entity.setDesiredDueAt(request.desiredDueAt());

        Request saved = requestRepository.save(entity);

        deadlineAssigner.assignDeadlines(saved);

        return new ClientRequestCreatedResponse(
                saved.getId(),
                saved.getTypeId(),
                saved.getSubject(),
                saved.getDescription(),
                saved.getDataParams(),
                saved.getPriority(),
                saved.getStatus(),
                saved.getDesiredDueAt(),
                saved.getCreatedAt().toInstant(),
                saved.getFirstResponseDueAt(),
                saved.getResolutionDueAt()
        );
    }
}

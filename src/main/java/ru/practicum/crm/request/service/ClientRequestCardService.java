package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.request.api.client.dto.ClientRequestCardResponse;
import ru.practicum.crm.request.api.client.dto.ClientRequestStatusHistoryItem;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatusChange;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestStatusHistoryRepository;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserContext;

@Service
public class ClientRequestCardService {

    private final RequestRepository requestRepository;
    private final RequestStatusHistoryRepository historyRepository;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "TenantContext is a Spring-managed dependency injected into the service."
    )
    private final TenantContext tenantContext;
    private final UserContext userContext;

    public ClientRequestCardService(
            RequestRepository requestRepository,
            RequestStatusHistoryRepository historyRepository,
            TenantContext tenantContext,
            UserContext userContext
    ) {
        this.requestRepository = requestRepository;
        this.historyRepository = historyRepository;
        this.tenantContext = tenantContext;
        this.userContext = userContext;
    }

    @PreAuthorize("hasAuthority('REQUEST_READ_OWN')")
    @Transactional(readOnly = true)
    public ClientRequestCardResponse getCard(UUID requestId) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        UUID userId = userContext.getCurrentUserId();

        if (tenantId == null || userId == null) {
            throw notFound();
        }

        Request request = requestRepository
                .findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .filter(item -> userId.equals(item.getAuthorId()))
                .orElseThrow(ClientRequestCardService::notFound);

        List<ClientRequestStatusHistoryItem> history = historyRepository
                .findByTenantIdAndRequestIdOrderByCreatedAtAsc(tenantId, requestId)
                .stream()
                .map(ClientRequestCardService::toHistoryItem)
                .toList();

        return new ClientRequestCardResponse(
                request.getId(),
                request.getTypeId(),
                request.getSubject(),
                request.getDescription(),
                request.getDataParams(),
                request.getPriority(),
                request.getStatus(),
                request.getCreatedAt().toInstant(),
                request.getDesiredDueAt(),
                request.getFirstResponseDueAt(),
                request.getResolutionDueAt(),
                history
        );
    }

    private static ClientRequestStatusHistoryItem toHistoryItem(
            RequestStatusChange change
    ) {
        return new ClientRequestStatusHistoryItem(
                change.getFromStatus(),
                change.getToStatus(),
                change.getCreatedAt().toInstant()
        );
    }

    private static ApiException notFound() {
        return new ApiException(
                ErrorCode.NOT_FOUND,
                ErrorCode.NOT_FOUND.getDefaultDetail()
        );
    }
}

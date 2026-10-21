package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

@Service
public class RequestCardService {

    private final RequestRepository requests;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость.")
    private final TenantContext tenantContext;

    public RequestCardService(RequestRepository requests, TenantContext tenantContext) {
        this.requests = requests;
        this.tenantContext = tenantContext;
    }

    @PreAuthorize("hasAuthority('REQUEST_READ_ALL')")
    @Transactional(readOnly = true)
    public RequestCardResponse get(UUID requestId) {
        Request request = requests
                .findByIdAndTenantIdAndDeletedFalse(requestId, tenantContext.getCurrentTenantId())
                .orElseThrow(RequestCardService::notFound);
        return toCard(request);
    }

    private static RequestCardResponse toCard(Request request) {
        return new RequestCardResponse(
                request.getId(), request.getSubject(), request.getDescription(),
                request.getDataParams(), request.getStatus(), request.getPriority(),
                request.getTypeId(), request.getAuthorId(), request.getAssigneeId(),
                request.getAssignedAt(), request.getCreatedAt(), request.getUpdatedAt(),
                request.getDesiredDueAt(), request.getFirstResponseDueAt(),
                request.getResolutionDueAt(), request.isFirstResponseOverdue(),
                request.isResolutionOverdue(), request.isOverdue(), request.getVersion());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
    }
}

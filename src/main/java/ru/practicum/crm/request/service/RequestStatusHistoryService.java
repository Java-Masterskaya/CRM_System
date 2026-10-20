package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.RequestStatusChange;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestStatusHistoryRepository;
import ru.practicum.crm.tenant.api.TenantContext;

@Service
public class RequestStatusHistoryService {

    private final RequestStatusHistoryRepository history;
    private final RequestRepository requests;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "TenantContext — Spring-бин.")
    private final TenantContext tenantContext;

    public RequestStatusHistoryService(RequestStatusHistoryRepository history,
                                       RequestRepository requests, TenantContext tenantContext) {
        this.history = history;
        this.requests = requests;
        this.tenantContext = tenantContext;
    }

    @PreAuthorize("hasAuthority('REQUEST_READ_ALL')")
    @Transactional(readOnly = true)
    public Page<RequestStatusChange> journal(UUID requestId, Pageable pageable) {
        Pageable bounded = PageRequests.requireBounded(pageable);
        UUID tenantId = tenantContext.getCurrentTenantId();
        if (!requests.existsByIdAndTenantIdAndDeletedFalse(requestId, tenantId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
        }
        return history.findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(tenantId, requestId,
                bounded);
    }
}

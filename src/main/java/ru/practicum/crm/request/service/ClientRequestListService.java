package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.client.dto.ClientRequestListItem;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestSpecifications;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Список заявок текущего клиента.
 */
@Service
public class ClientRequestListService {

    public static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "subject", "subject",
            "status", "status",
            "priority", "priorityRank");

    public static final String DEFAULT_SORT = "createdAt,desc";

    private final RequestRepository requests;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "TenantContext is a Spring-managed"
                    + " dependency injected into the service."
    )
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;

    public ClientRequestListService(
            RequestRepository requests,
            TenantContext tenantContext,
            CurrentUser currentUser
    ) {
        this.requests = requests;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
    }

    /**
     * Возвращает страницу заявок текущего клиента.
     */
    @PreAuthorize("hasAuthority('REQUEST_READ_OWN')")
    @Transactional(readOnly = true)
    public PageResponse<ClientRequestListItem> list(
            Set<RequestStatus> statuses,
            Pageable pageable
    ) {
        Specification<Request> specification =
                RequestSpecifications.ofTenant(tenantContext.getCurrentTenantId())
                        .and(RequestSpecifications.ofAuthor(currentUser.id()));

        if (statuses != null && !statuses.isEmpty()) {
            specification = specification.and(
                    RequestSpecifications.inStatuses(statuses));
        }

        Pageable boundedPageable = PageRequests.requireBounded(pageable);
        Page<Request> page = requests.findAll(
                specification,
                withEntityFields(boundedPageable));

        return PageResponse.of(page.map(ClientRequestListService::toItem));
    }

    private static Pageable withEntityFields(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> order.withProperty(
                        SORT_FIELDS.getOrDefault(
                                order.getProperty(),
                                order.getProperty())))
                .toList());

        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort);
    }

    private static ClientRequestListItem toItem(Request request) {
        return new ClientRequestListItem(
                request.getId(),
                request.getSubject(),
                request.getStatus(),
                request.getPriority(),
                request.getTypeId(),
                request.getDesiredDueAt(),
                request.getCreatedAt());
    }
}

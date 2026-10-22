package ru.practicum.crm.request.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.dto.RequestAuditEntryItem;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.api.dto.RequestStatusHistoryItem;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestStatusChange;
import ru.practicum.crm.request.service.RequestAuditService;
import ru.practicum.crm.request.service.RequestCardService;
import ru.practicum.crm.request.service.RequestStatusHistoryService;
import ru.practicum.crm.tenant.api.TenantContext;

@RestController
@RequestMapping("/admin/requests/{requestId}")
public class RequestCardController {

    private static final List<String> NO_SORT = List.of();
    private static final Set<String> NO_SORT_FIELDS = Set.of();

    private final RequestCardService cardService;
    private final RequestStatusHistoryService historyService;
    private final RequestAuditService auditService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость.")
    private final TenantContext tenantContext;

    public RequestCardController(RequestCardService cardService,
            RequestStatusHistoryService historyService, RequestAuditService auditService,
            TenantContext tenantContext) {
        this.cardService = cardService;
        this.historyService = historyService;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public RequestCardResponse card(@PathVariable UUID requestId) {
        return cardService.get(requestId);
    }

    @GetMapping("/status-history")
    public PageResponse<RequestStatusHistoryItem> statusHistory(@PathVariable UUID requestId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequests.toPageable(page, size, NO_SORT, NO_SORT_FIELDS);
        Page<RequestStatusChange> result = historyService.journal(requestId, pageable);
        return PageResponse.of(result.map(RequestCardController::toHistoryItem));
    }

    @GetMapping("/audit-log")
    public PageResponse<RequestAuditEntryItem> auditLog(@PathVariable UUID requestId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequests.toPageable(page, size, NO_SORT, NO_SORT_FIELDS);
        Page<RequestAuditEntry> result = auditService.journal(
                tenantContext.getCurrentTenantId(), requestId, pageable);
        return PageResponse.of(result.map(RequestCardController::toAuditItem));
    }

    private static RequestStatusHistoryItem toHistoryItem(RequestStatusChange change) {
        return new RequestStatusHistoryItem(change.getId(), change.getFromStatus(),
                change.getToStatus(), change.getAuthorId(), change.getComment(),
                change.getCreatedAt());
    }

    private static RequestAuditEntryItem toAuditItem(RequestAuditEntry entry) {
        return new RequestAuditEntryItem(entry.getId(), entry.getField(), entry.getOldValue(),
                entry.getNewValue(), entry.getAuthorId(), entry.getCreatedAt());
    }
}

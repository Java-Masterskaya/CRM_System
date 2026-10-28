package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.model.NotDeletedFilter;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

@Service
@RequiredArgsConstructor
public class RequestSoftDeleteService {

    private final RequestRepository requestRepository;
    private final EntityManager entityManager;
    private final RequestAuditService auditService;
    private final CurrentUser currentUser;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "TenantContext — Spring-бин.")
    private final TenantContext tenantContext;

    /**
     * Мягко удаляет заявку текущего арендатора.
     *
     * @throws org.springframework.security.access.AccessDeniedException без {@code REQUEST_DELETE}
     * @throws NotFoundException если заявки нет или она уже удалена
     * */
    @PreAuthorize("hasAuthority('REQUEST_DELETE')")
    @Transactional
    public void delete(UUID requestId) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        Request request = requestRepository.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(RequestSoftDeleteService::requestNotFound);
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.softDelete(Instant.now());
        requestRepository.save(request);
        auditService.record(request, before, currentUser.id());
    }

    /**
     * Восстанавливает мягко удаленную заявку в прежнем статусе.
     *
     * @throws org.springframework.security.access.AccessDeniedException без {@code REQUEST_DELETE}
     * @throws NotFoundException если заявки нет у арендатора
     * */
    @PreAuthorize("hasAuthority('REQUEST_DELETE')")
    @Transactional
    public RequestStatusResponse restore(UUID requestId) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        NotDeletedFilter.disable(entityManager);

        try {
            Request request = requestRepository.findByIdAndTenantId(requestId, tenantId)
                    .orElseThrow(RequestSoftDeleteService::requestNotFound);
            if (!request.isDeleted()) {
                throw new ApiException(ErrorCode.REQUEST_NOT_DELETED,
                        "Заявка не удаленна. Восстанавливать нечего.");
            }
            final RequestSnapshot before = RequestSnapshot.of(request);
            request.restore();
            Request saved = requestRepository.saveAndFlush(request);
            auditService.record(saved, before, currentUser.id());
            return new RequestStatusResponse(saved.getId(), saved.getStatus(), saved.getVersion());
        } finally {
            NotDeletedFilter.enable(entityManager);
        }
    }

    private static ApiException requestNotFound() {
        return new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
    }
}

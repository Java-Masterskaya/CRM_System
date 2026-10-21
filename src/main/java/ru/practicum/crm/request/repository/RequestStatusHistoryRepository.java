package ru.practicum.crm.request.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.RequestStatusChange;

public interface RequestStatusHistoryRepository extends Repository<RequestStatusChange, UUID> {

    <S extends RequestStatusChange> S save(S change);

    Page<RequestStatusChange> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID tenantId,
            UUID requestId, Pageable pageable);
}


package ru.practicum.crm.request.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.RequestStatusChange;

/**
 * История статусов заявок. Операций изменения и удаления здесь нет: история только пополняется.
 */
public interface RequestStatusHistoryRepository extends Repository<RequestStatusChange, UUID> {

    <S extends RequestStatusChange> S save(S change);

    List<RequestStatusChange> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(
            UUID tenantId,
            UUID requestId);

    Page<RequestStatusChange> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(
            UUID tenantId,
            UUID requestId,
            Pageable pageable);
}

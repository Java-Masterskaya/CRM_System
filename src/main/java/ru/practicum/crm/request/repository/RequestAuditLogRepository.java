package ru.practicum.crm.request.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.RequestAuditEntry;

/**
 * Журнал аудита заявок. Операций изменения и удаления здесь нет: журнал только пополняется
 * и читается, а чтение всегда с арендатором.
 */
public interface RequestAuditLogRepository extends Repository<RequestAuditEntry, UUID> {

    <S extends RequestAuditEntry> List<S> saveAll(Iterable<S> entries);

    /** Журнал одной заявки в порядке изменений; {@code id} — последний ключ сортировки. */
    List<RequestAuditEntry> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID tenantId,
            UUID requestId);
}

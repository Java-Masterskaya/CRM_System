package ru.practicum.crm.attachment.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.attachment.domain.RequestAttachment;

/**
 * Вложения заявок. Как и в остальных репозиториях проекта, операции перечислены поимённо, а
 * чтение — только с арендатором: вложение другого арендатора найти нельзя.
 */
public interface RequestAttachmentRepository extends Repository<RequestAttachment, UUID> {

    RequestAttachment save(RequestAttachment attachment);

    Optional<RequestAttachment> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Привязан ли уже этот объект медиа-сервиса к этой заявке. */
    boolean existsByTenantIdAndRequestIdAndObjectId(UUID tenantId, UUID requestId,
            String objectId);

    /**
     * Страница вложений одной заявки в порядке добавления. {@code id} — последний ключ
     * сортировки, поэтому вложения с одинаковым временем идут в одном и том же порядке на всех
     * страницах.
     */
    Page<RequestAttachment> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID tenantId,
            UUID requestId, Pageable pageable);
}

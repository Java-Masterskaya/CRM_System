package ru.practicum.crm.comment.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.comment.domain.RequestComment;

/**
 * Комментарии заявок. Как и в остальных репозиториях проекта, операции перечислены поимённо, а
 * чтение — только с арендатором: комментарий другого арендатора найти нельзя.
 */
public interface RequestCommentRepository extends Repository<RequestComment, UUID> {

    RequestComment save(RequestComment comment);

    Optional<RequestComment> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Страница всех комментариев одной заявки, включая внутренние, в порядке написания — только
     * для команды. {@code id} — последний ключ сортировки, поэтому комментарии с одинаковым
     * временем идут в одном и том же порядке на всех страницах.
     */
    Page<RequestComment> findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID tenantId,
            UUID requestId, Pageable pageable);

    /**
     * Страница комментариев заявки, видимых клиенту, в том же порядке. Клиентский контур читает
     * комментарии только этим методом: внутренние в выборку не попадают (SPEC §5.2).
     */
    Page<RequestComment> findByTenantIdAndRequestIdAndVisibleToClientTrueOrderByCreatedAtAscIdAsc(
            UUID tenantId, UUID requestId, Pageable pageable);
}

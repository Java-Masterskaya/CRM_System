package ru.practicum.crm.request.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.FieldChange;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestAuditLogRepository;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Журнал аудита изменений заявки (T-046, ТЗ §6.7).
 *
 * <p>Код, который правит заявку, делает снимок значимых полей до правки и после неё передаёт его
 * сюда в той же транзакции:
 * <pre>{@code
 * final RequestSnapshot before = RequestSnapshot.of(request);
 * request.setPriority(RequestPriority.HIGH);
 * audit.record(request, before, actorId);
 * }</pre>
 * Если транзакция откатится, пропадут и изменение, и записи о нём.
 *
 * <p>Право {@code REQUEST_AUDIT_READ} на чтение журнала (T-041) проверяет
 * {@code @PreAuthorize} - оно отдельное от {@code REQUEST_READ_ALL}: сегодня у OPERATOR и ADMIN
 * оба права выданы вместе, но проверяться они должны по отдельности.
 */
@Service
public class RequestAuditService {

    private final RequestAuditLogRepository repository;
    private final RequestRepository requests;

    public RequestAuditService(RequestAuditLogRepository repository, RequestRepository requests) {
        this.repository = repository;
        this.requests = requests;
    }

    /**
     * Записывает в журнал все значимые поля, которые изменились с момента снимка {@code before}.
     * Работает только внутри уже открытой транзакции — той же, что меняет заявку.
     *
     * @return записанные изменения; пусто, если значимые поля не менялись
     * @throws IllegalArgumentException если автор не указан
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<FieldChange> record(Request request, RequestSnapshot before, UUID authorId) {
        if (authorId == null) {
            throw new IllegalArgumentException("Не указан автор изменения");
        }
        List<FieldChange> changes = before.changesTo(RequestSnapshot.of(request));
        repository.saveAll(changes.stream()
                .map(change -> new RequestAuditEntry(request.getTenantId(), request.getId(),
                        change, authorId))
                .toList());
        return changes;
    }

    /**
     * Страница журнала заявки арендатора в порядке изменений (#161, T-041).
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}: без страниц читать нельзя, размер — не больше
     *     {@link PageRequests#MAX_SIZE}
     * @throws org.springframework.security.access.AccessDeniedException без права
     *     {@code REQUEST_AUDIT_READ} - 403
     * @throws NotFoundException если заявки нет у арендатора - проверяется после валидации
     *     страницы, но до чтения журнала, иначе чужой {@code requestId} вернул бы пустую
     *     страницу вместо 404
     */
    @PreAuthorize("hasAuthority('REQUEST_AUDIT_READ')")
    @Transactional(readOnly = true)
    public Page<RequestAuditEntry> journal(UUID tenantId, UUID requestId, Pageable pageable) {
        Pageable bounded = PageRequests.requireBounded(pageable);
        if (!requests.existsByIdAndTenantIdAndDeletedFalse(requestId, tenantId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
        }
        return repository.findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(tenantId, requestId,
                bounded);
    }
}

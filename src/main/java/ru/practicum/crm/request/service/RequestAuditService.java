package ru.practicum.crm.request.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.request.domain.FieldChange;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestAuditLogRepository;

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
 * Если транзакция откатится, пропадут и изменение, и записи о нём. {@code final} у снимка —
 * подсказка читателю, что это состояние до правки; checkstyle без него не пропустит снимок,
 * объявленный далеко от вызова {@code record}.
 *
 * <p>Автор приходит параметром: его источником станет пользователь из токена, когда появится
 * проверка токена (T-026). Кому показывать журнал, решает вызывающий код — права (T-030).
 */
@Service
public class RequestAuditService {

    private final RequestAuditLogRepository repository;

    public RequestAuditService(RequestAuditLogRepository repository) {
        this.repository = repository;
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

    /** Журнал заявки арендатора в порядке изменений. */
    @Transactional(readOnly = true)
    public List<RequestAuditEntry> journal(UUID tenantId, UUID requestId) {
        return repository.findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(tenantId, requestId);
    }
}

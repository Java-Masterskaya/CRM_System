package ru.practicum.crm.outbox.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Запись исходящих событий в транзакции вызывающего (T-065).
 *
 * <p>Зависимости — репозиторий и контекст арендатора: отправителей и почты здесь нет, поэтому
 * запись физически не может ничего отправить. {@link Propagation#MANDATORY} не даёт записать
 * событие вне транзакции операции.
 *
 * <p>Входные данные проверяются сразу: иначе пустой тип или нагрузка всплыли бы только при
 * фиксации, ошибкой базы, и откатили бы операцию с непонятной причиной. Ошибка проверки
 * ({@link IllegalArgumentException}) транзакцию вызывающего на откат не помечает: проверка идёт
 * до записи, откатывать нечего. Без {@code noRollbackFor} вызывающий, поймавший ошибку и
 * продолживший работу, получил бы при фиксации {@code UnexpectedRollbackException}.
 */
@Service
public class OutboxEventPublisher implements OutboxPublisher {

    private final OutboxEventRepository repository;
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public OutboxEventPublisher(OutboxEventRepository repository, TenantContext tenantContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY,
            noRollbackFor = IllegalArgumentException.class)
    public UUID publish(UUID tenantId, String eventType, Map<String, Object> payload) {
        requireEventData(tenantId, eventType, payload);
        return repository.save(new OutboxEvent(tenantId, eventType, payload)).getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY,
            noRollbackFor = IllegalArgumentException.class)
    public UUID publish(UUID tenantId, String eventType, String aggregateType, UUID aggregateId,
            Map<String, Object> payload) {
        requireEventData(tenantId, eventType, payload);
        if (aggregateType != null && aggregateType.length() > OutboxEvent.TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Вид объекта длиннее " + OutboxEvent.TYPE_MAX_LENGTH + " символов");
        }
        return repository.save(OutboxEvent.aboutObject(tenantId, eventType, aggregateType,
                aggregateId, payload)).getId();
    }

    private void requireEventData(UUID tenantId, String eventType, Map<String, Object> payload) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Не указан арендатор события");
        }
        requireCurrentTenant(tenantId);
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Не указан тип события");
        }
        if (eventType.length() > OutboxEvent.TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Тип события длиннее " + OutboxEvent.TYPE_MAX_LENGTH + " символов");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Не передана полезная нагрузка события");
        }
    }

    /**
     * Запрос арендатора пишет события только своего арендатора. RLS отклонил бы чужое событие и
     * так, но ошибкой базы при фиксации; здесь — сразу и понятной ошибкой. У фоновых задач
     * контекст арендатора пуст: они работают за всех арендаторов, и сверять не с чем.
     */
    private void requireCurrentTenant(UUID tenantId) {
        UUID current = tenantContext.getCurrentTenantId();
        if (current != null && !current.equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Событие для другого арендатора, чем арендатор текущего запроса");
        }
    }
}

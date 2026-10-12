package ru.practicum.crm.outbox.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Запись исходящих событий в транзакции вызывающего (T-065).
 *
 * <p>Единственная зависимость — репозиторий: отправителей и почты здесь нет, поэтому запись
 * физически не может ничего отправить. {@link Propagation#MANDATORY} не даёт записать событие
 * вне транзакции операции.
 *
 * <p>Входные данные проверяются сразу: иначе пустой тип или нагрузка всплыли бы только при
 * фиксации, ошибкой базы, и откатили бы операцию с непонятной причиной.
 */
@Service
public class OutboxEventPublisher implements OutboxPublisher {

    private final OutboxEventRepository repository;

    public OutboxEventPublisher(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(UUID tenantId, String eventType, Map<String, Object> payload) {
        requireEventData(tenantId, eventType, payload);
        return repository.save(new OutboxEvent(tenantId, eventType, payload)).getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
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

    private static void requireEventData(UUID tenantId, String eventType,
            Map<String, Object> payload) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Не указан арендатор события");
        }
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
}

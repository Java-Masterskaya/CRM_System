package ru.practicum.crm.outbox.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;

/**
 * Доступ к исходящим событиям.
 *
 * <p>Здесь две разные по смыслу группы методов. Выборка готовых к отправке — работа фонового
 * обработчика, который обслуживает всех арендаторов сразу, поэтому арендатора не принимает.
 * Всё остальное — просмотр журнала событий конкретного арендатора, и такие методы без
 * арендатора не вызываются.
 *
 * <p>Как и в остальных репозиториях проекта, интерфейс наследует маркерный {@link Repository}
 * и перечисляет операции поимённо: удаления и слепого {@code findAll} здесь нет.
 */
public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    <S extends OutboxEvent> S save(S event);

    /**
     * Записи, которые пора отправлять: в нужном состоянии и с наступившим временем попытки.
     * Порядок — от самой давно ожидающей. Ограничение размера порции задаёт вызывающий:
     * обработчик берёт события пачками, а не все сразу.
     */
    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            OutboxStatus status, Instant moment, Pageable limit);

    Optional<OutboxEvent> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, OutboxStatus status);
}

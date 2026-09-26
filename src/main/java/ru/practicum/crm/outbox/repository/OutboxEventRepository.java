package ru.practicum.crm.outbox.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;

/**
 * Доступ к исходящим событиям.
 *
 * <p>Здесь две разные по смыслу группы методов. Захват порции, поиск взятого события и
 * показатели очереди для метрик — служебная работа, которая охватывает всех арендаторов сразу,
 * поэтому арендатора эти методы не принимают. Всё остальное — просмотр журнала событий
 * конкретного арендатора, и такие методы без арендатора не вызываются.
 *
 * <p>Как и в остальных репозиториях проекта, интерфейс наследует маркерный {@link Repository}
 * и перечисляет операции поимённо: удаления и слепого {@code findAll} здесь нет.
 */
public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    <S extends OutboxEvent> S save(S event);

    /**
     * Блокирует и возвращает порцию событий, которые пора отправлять: новые с наступившим
     * временем попытки и взятые в работу, у которых истёк срок аренды (обработчик, взявший их,
     * упал или не успел). Порядок — от самого давно ожидающего.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — строки, которые уже заблокировал другой обработчик,
     * пропускаются, а не ждут: два экземпляра приложения одновременно получат разные события.
     * Блокировка живёт до конца транзакции, поэтому метод вызывается только внутри неё
     * ({@link Propagation#MANDATORY}); вызывающий успевает в той же транзакции перевести
     * события в {@link OutboxStatus#IN_PROGRESS}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value =
            """
            SELECT * FROM outbox_events
            WHERE status IN ('NEW', 'IN_PROGRESS') AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> lockReadyBatch(@Param("now") Instant now, @Param("limit") int limit);

    /** Событие, взятое в обработку, — чтобы записать результат попытки. */
    Optional<OutboxEvent> findByIdAndStatus(UUID id, OutboxStatus status);

    /** Сколько событий в этом состоянии во всей очереди — для метрик. */
    long countByStatus(OutboxStatus status);

    /** Когда создано самое давнее событие в этих состояниях; пусто, если таких событий нет. */
    @Query("SELECT min(e.createdAt) FROM OutboxEvent e WHERE e.status IN :statuses")
    Optional<Instant> findOldestCreatedAt(@Param("statuses") Collection<OutboxStatus> statuses);

    Optional<OutboxEvent> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, OutboxStatus status);
}

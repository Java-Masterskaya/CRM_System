package ru.practicum.crm.request.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Доступ к заявкам.
 *
 * <p>Интерфейс наследует маркерный {@link Repository}, а не {@code JpaRepository}, и
 * перечисляет доступные операции поимённо. Иначе наружу вышли бы {@code findById},
 * {@code findAll}, {@code deleteById} и {@code count} — методы без арендатора, через которые
 * однажды утекут чужие данные. Каждое чтение здесь принимает арендатора и отсекает удалённые
 * заявки; централизованная фильтрация появится в T-015 (#16) и эти сигнатуры не отменит.
 * Исключение — пометка просрочки: её делает фоновая проверка по всем арендаторам сразу.
 */
public interface RequestRepository extends Repository<Request, UUID> {

    <S extends Request> S save(S request);

    Optional<Request> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    Page<Request> findByTenantIdAndDeletedFalse(UUID tenantId, Pageable pageable);

    Page<Request> findByTenantIdAndStatusAndDeletedFalse(UUID tenantId, RequestStatus status,
            Pageable pageable);

    boolean existsByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    /**
     * Просроченные заявки арендатора — по любому из сроков; для фильтра реестра (T-040).
     * Обслуживается частичным индексом {@code idx_requests_tenant_overdue}.
     */
    @Query(
            """
            SELECT r FROM Request r
            WHERE r.tenantId = :tenantId AND r.deleted = false
              AND (r.firstResponseOverdue = true OR r.resolutionOverdue = true)
            """)
    Page<Request> findOverdueByTenantId(@Param("tenantId") UUID tenantId, Pageable pageable);

    /**
     * Помечает порцию заявок, у которых истёк срок первого ответа, а они всё ещё в
     * {@link RequestStatus#NEW}: первый ответ — это переход в {@code CONTACTED}.
     *
     * <p>Порция берётся с {@code FOR UPDATE SKIP LOCKED}: строки, которые уже держит другой
     * экземпляр приложения, пропускаются, а не ждут. Версия заявки растёт, как при любом
     * изменении: администратор, открывший заявку до пометки, получит «данные устарели», а не
     * затрёт признак своей сохранённой копией. Условие совпадает с частичным индексом
     * {@code idx_requests_first_response_check} — менять только вместе.
     *
     * @param now момент проверки: сроки, истёкшие до него
     * @param limit размер порции
     * @return сколько заявок помечено
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value =
            """
            UPDATE requests
            SET first_response_overdue = TRUE, version = version + 1, updated_at = now()
            WHERE id IN (
                SELECT id FROM requests
                WHERE status = 'NEW' AND NOT deleted AND NOT first_response_overdue
                  AND first_response_due_at <= :now
                ORDER BY first_response_due_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """)
    int markFirstResponseOverdue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Помечает порцию заявок, у которых истёк срок решения, а они не завершены и не ждут ответа
     * клиента: в {@code ON_HOLD} отсчёт приостановлен (T-059), а в конечных статусах заявка уже
     * закрыта. Остальное — как в {@link #markFirstResponseOverdue}; индекс —
     * {@code idx_requests_resolution_check}.
     *
     * @param now момент проверки: сроки, истёкшие до него
     * @param limit размер порции
     * @return сколько заявок помечено
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value =
            """
            UPDATE requests
            SET resolution_overdue = TRUE, version = version + 1, updated_at = now()
            WHERE id IN (
                SELECT id FROM requests
                WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS') AND NOT deleted
                  AND NOT resolution_overdue AND resolution_due_at <= :now
                ORDER BY resolution_due_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """)
    int markResolutionOverdue(@Param("now") Instant now, @Param("limit") int limit);
}

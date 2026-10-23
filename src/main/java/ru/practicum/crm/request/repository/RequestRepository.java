package ru.practicum.crm.request.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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

    /**
     * Сохраняет и сразу пишет изменения в базу. Версия заявки растёт при записи, а вызывающему
     * нужна уже новая — её получает клиент для следующего изменения (T-044). Одновременное
     * изменение той же версии тоже обнаруживается здесь, а не при фиксации транзакции.
     */
    <S extends Request> S saveAndFlush(S request);

    Optional<Request> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    Optional<Request> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Заявка арендатора без отсечения удаленных - для мягкого восстоновления.
     */
    Optional<Request> findByIdAndTenantIdAndAuthorIdAndDeletedFalse(
            UUID id, UUID tenantId, UUID authorId);

    Page<Request> findByTenantIdAndDeletedFalse(UUID tenantId, Pageable pageable);

    Page<Request> findByTenantIdAndAuthorIdAndDeletedFalse(UUID tenantId, UUID authorId,
                                                           Pageable pageable);

    Page<Request> findByTenantIdAndStatusAndDeletedFalse(UUID tenantId, RequestStatus status,
                                                         Pageable pageable);

    boolean existsByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    /**
     * Страница заявок по условию — для реестра с фильтрами (T-040). Условие собирается из
     * {@link RequestSpecifications} и всегда начинается с {@link RequestSpecifications#ofTenant}.
     *
     * <p>Метод взят из {@code JpaSpecificationExecutor} поимённо, как и остальные: целиком этот
     * интерфейс открыл бы, например, удаление по условию.
     */
    Page<Request> findAll(Specification<Request> specification, Pageable pageable);

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
            WITH marked AS (
                UPDATE requests
                SET first_response_overdue = TRUE, version = version + 1, updated_at = now()
                WHERE id IN (
                    SELECT id FROM requests
                    WHERE status = 'NEW' AND NOT deleted AND NOT first_response_overdue
                      AND first_response_due_at <= :now
                    ORDER BY first_response_due_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                    RETURNING tenant_id
            )
            SELECT tenant_id from marked
            """)
    List<UUID> markFirstResponseOverdue(@Param("now") Instant now, @Param("limit") int limit);

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
            WITH marked AS (
                UPDATE requests
                SET resolution_overdue = TRUE, version = version + 1, updated_at = now()
                WHERE id IN (
                    SELECT id FROM requests
                    WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS') AND NOT deleted
                      AND NOT resolution_overdue AND resolution_due_at <= :now
                    ORDER BY resolution_due_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING tenant_id
            )
            SELECT tenant_id FROM marked
            """)
    List<UUID> markResolutionOverdue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Блокирует порцию заявок, у которых просрочен срок первого ответа, а эскалация по нему ещё
     * не отправлена (T-061). Строки, которые держит другой экземпляр приложения, пропускаются.
     *
     * <p>Статус — тот же, что при пометке ({@link #markFirstResponseOverdue}): заявка всё ещё в
     * {@link RequestStatus#NEW}. Если на неё успели ответить между пометкой и эскалацией —
     * например, проход прервался и эскалация досталась следующему, — письмо уже не нужно.
     * Условие совпадает с частичным индексом {@code idx_requests_first_response_escalation} —
     * менять только вместе.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value =
            """
            SELECT * FROM requests
            WHERE status = 'NEW' AND first_response_overdue AND NOT first_response_escalated
              AND NOT deleted
            ORDER BY first_response_due_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<Request> lockFirstResponseToEscalate(@Param("limit") int limit);

    /**
     * То же для срока решения. Статус — как в {@link #markResolutionOverdue}: заявку, которую
     * закрыли или поставили на ожидание между пометкой и эскалацией, письмо не беспокоит. Индекс —
     * {@code idx_requests_resolution_escalation}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value =
            """
            SELECT * FROM requests
            WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS') AND resolution_overdue
              AND NOT resolution_escalated AND NOT deleted
            ORDER BY resolution_due_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<Request> lockResolutionToEscalate(@Param("limit") int limit);

    /**
     * Отмечает, что письма о просрочке первого ответа записаны. Версия заявки не растёт: признак
     * не отображается в сущности, и сохранение заявки его не затрёт.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true,
            value = "UPDATE requests SET first_response_escalated = TRUE WHERE id IN (:ids)")
    int markFirstResponseEscalated(@Param("ids") Collection<UUID> ids);

    /** То же для срока решения. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true,
            value = "UPDATE requests SET resolution_escalated = TRUE WHERE id IN (:ids)")
    int markResolutionEscalated(@Param("ids") Collection<UUID> ids);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE requests
                    SET assignee_id = :actorId,
                        assigned_at = :assignedAt,
                        updated_at = CURRENT_TIMESTAMP,
                        version = version + 1
                    WHERE id = :requestId
                      AND tenant_id = :tenantId
                      AND deleted = FALSE
                      AND assignee_id IS NULL
                      AND status NOT IN ('DONE', 'REJECTED', 'CANCELLED')
                    """
    )
    int takeIfUnassigned(
            @Param("tenantId") UUID tenantId,
            @Param("requestId") UUID requestId,
            @Param("actorId") UUID actorId,
            @Param("assignedAt") Instant assignedAt
    );
}

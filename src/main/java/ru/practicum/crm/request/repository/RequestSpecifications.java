package ru.practicum.crm.request.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Условия выборки заявок для реестра (T-040). Каждое условие — отдельная спецификация, реестр
 * складывает из них только те, что заданы в запросе: так в SQL попадают лишь нужные условия, и
 * планировщик может взять индекс под каждое.
 *
 * <p>Любая выборка начинается с {@link #ofTenant}: без арендатора спецификацию не составить, и
 * чужие заявки в реестр не попадут ни при каком сочетании фильтров.
 */
public final class RequestSpecifications {

    /** Статусы, в которых заявка ещё не завершена и срок решения идёт. */
    private static final List<RequestStatus> NOT_FINISHED = Arrays.stream(RequestStatus.values())
            .filter(status -> !status.isTerminal())
            .toList();

    private RequestSpecifications() {
    }

    /** Заявки арендатора, кроме удалённых. Индексы реестра начинаются с {@code tenant_id}. */
    public static Specification<Request> ofTenant(UUID tenantId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isFalse(root.get("deleted")));
    }

    /**
     * Только заявки указанного автора.
     *
     * <p>Применяется вместе с {@link #ofTenant(UUID)}: фильтр арендатора
     * задаёт границу данных, а фильтр автора ограничивает их собственными заявками.
     */
    public static Specification<Request> ofAuthor(UUID authorId) {
        return (root, query, cb) ->
                cb.equal(root.get("authorId"), authorId);
    }

    /** В одном из статусов. Индекс — {@code idx_requests_tenant_status}. */
    public static Specification<Request> inStatuses(Collection<RequestStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /** Не завершены: статус не терминальный. */
    public static Specification<Request> notFinished() {
        return inStatuses(NOT_FINISHED);
    }

    /**
     * Назначены на этого пользователя (очередь оператора, T-052). Индекс —
     * {@code idx_requests_tenant_assignee_queue}.
     */
    public static Specification<Request> assignedTo(UUID assigneeId) {
        return (root, query, cb) -> cb.equal(root.get("assigneeId"), assigneeId);
    }

    /**
     * Заданного типа. Индекс — {@code idx_requests_type_id}: тип принадлежит одному арендатору, и
     * индекс по типу уже сужает выборку до него — составной с {@code tenant_id} его бы дублировал.
     */
    public static Specification<Request> ofType(UUID typeId) {
        return (root, query, cb) -> cb.equal(root.get("typeId"), typeId);
    }

    /**
     * С заданным приоритетом. Условие — по рангу: он однозначно соответствует приоритету
     * (ограничение {@code requests_priority_check}), и под него есть индекс
     * {@code idx_requests_tenant_priority_rank}.
     */
    public static Specification<Request> ofPriority(RequestPriority priority) {
        return (root, query, cb) -> cb.equal(root.get("priorityRank"), priority.getRank());
    }

    /** Созданы не раньше момента. Индекс — {@code idx_requests_tenant_created_at}. */
    public static Specification<Request> createdFrom(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<OffsetDateTime>get("createdAt"),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
    }

    /** Созданы раньше момента: граница периода не входит в него. */
    public static Specification<Request> createdBefore(Instant to) {
        return (root, query, cb) -> cb.lessThan(root.<OffsetDateTime>get("createdAt"),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    /**
     * Просрочены сейчас — то же правило, что {@link Request#isOverdue()}: срок первого ответа
     * истёк, пока заявка в {@code NEW}, или срок решения истёк, а заявка не завершена. Закрытая с
     * нарушением заявка сюда не попадает, хотя признак просрочки у неё остаётся.
     *
     * <p>Индекс — {@code idx_requests_tenant_overdue}: в нём заявки с любым из признаков, а
     * статус проверяется уже среди них. Условие индекса следует из этого условия при любых
     * значениях статуса, поэтому индекс подходит и запросу с параметрами.
     */
    public static Specification<Request> overdue() {
        return (root, query, cb) -> cb.or(
                cb.and(cb.equal(root.get("status"), RequestStatus.NEW),
                        cb.isTrue(root.get("firstResponseOverdue"))),
                cb.and(root.get("status").in(NOT_FINISHED),
                        cb.isTrue(root.get("resolutionOverdue"))));
    }
}

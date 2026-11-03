package ru.practicum.crm.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.practicum.crm.common.model.NotDeletedFilter;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Заявка на обработку данных — главная сущность продукта (ТЗ §4.2).
 *
 * <p>Состав полей зафиксирован в T-033 (#34). Правила переходов статусов (T-043),
 * выбор приоритета новой заявки ({@link RequestPriority#resolve}) и расчёт сроков по SLA (T-058,
 * {@code RequestDeadlineAssigner}) находятся за пределами сущности: здесь только хранение
 * состояния.
 * Мягкое удаление (T-047); Запись не стираеться - ставится {@code #deleted} и {@code #deletedAt}.
 * Hibernate filter {@code NotDeleteFilter} скрывает такие заявки из обычных
 * чтений рядом с фильтрами арендатора
 */
@Entity
@Table(name = "requests")
@Getter
@FilterDef(name = NotDeletedFilter.NAME)
@Filter(name = NotDeletedFilter.NAME, condition = "deleted = false")
@ToString
public class Request extends TenantScopedEntity {

    /**
     * Вид объекта в ссылке исходящего события о заявке: по ней выбираются все события одной
     * заявки (письма об эскалации T-061, о смене статуса T-070).
     */
    public static final String AGGREGATE_TYPE = "REQUEST";

    @Setter
    @Column(name = "type_id")
    private UUID typeId;

    @Setter
    @Column(name = "subject", nullable = false)
    private String subject;

    @Setter
    @Column(name = "description", nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_params")
    private Map<String, Object> dataParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private RequestPriority priority = RequestPriority.FALLBACK;

    /**
     * Ранг приоритета для сортировки по срочности. Сеттера нет: значение меняется только
     * вместе с приоритетом в {@link #setPriority}, а в базе их соответствие проверяет
     * ограничение {@code requests_priority_check}.
     */
    @Column(name = "priority_rank", nullable = false)
    private short priorityRank = RequestPriority.FALLBACK.getRank();

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Setter
    @Column(name = "desired_due_at")
    private Instant desiredDueAt;

    @Setter
    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    @Setter
    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    /**
     * Срок первого ответа истёк, пока заявка была в {@link RequestStatus#NEW}. Ставит фоновая
     * проверка (T-060); на жизненный цикл не влияет.
     */
    @Setter
    @Column(name = "first_response_overdue", nullable = false)
    private boolean firstResponseOverdue;

    /**
     * Срок решения истёк, пока заявка не была завершена. Ставит фоновая проверка (T-060); на
     * жизненный цикл не влияет.
     */
    @Setter
    @Column(name = "resolution_overdue", nullable = false)
    private boolean resolutionOverdue;

    @Setter
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    /**
     * Переменная для обозначения момента мягкого удаления, {@code null} пока заявка не удалена.
     * */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Request() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    /**
     * Создаёт заявку с полями, без которых её существование бессмысленно.
     *
     * <p>Остальные поля заполняются сеттерами по мере того, как их источники появятся:
     * тип и приоритет — при создании из клиентского API, сроки — из SLA-политики,
     * исполнитель вместе с моментом назначения — методом {@link #changeAssignee}.
     */
    public Request(UUID tenantId, UUID authorId, String subject, String description,
                   RequestStatus status) {
        super(tenantId);
        this.authorId = authorId;
        this.subject = subject;
        this.description = description;
        this.status = status;
    }

    /**
     * Назначает, переназначает или снимает исполнителя и вместе с ним - момент, когда это
     * произошло.
     *
     * @param occurredAt момент назначения; обязателен при назначении — при снятии не
     *     используется, но тоже проверяется, чтобы у метода была одна понятная сигнатура.
     * @return {@code true}, если исполнитель или факт его отсутствия сменился
     * @throws IllegalArgumentException если назначается исполнитель, а момент не передан
     */
    public boolean changeAssignee(UUID newAssigneeId, Instant occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("Момент назначения обязателен");
        }
        if (Objects.equals(assigneeId, newAssigneeId)) {
            return false;
        }
        assigneeId = newAssigneeId;
        assignedAt = newAssigneeId == null ? null : occurredAt;
        return true;
    }

    public boolean softDelete(Instant occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("Момент удаление обязателен");
        }

        if (deleted) {
            return false;
        }

        deleted = true;
        deletedAt = occurredAt;
        return true;
    }

    /**
     * Снимает мягкое удаление. Статус и остальные поля остаються как были на момент удаления.
     * */
    public boolean restore() {
        if (!deleted) {
            return false;
        }

        deleted = false;
        deletedAt = null;
        return true;
    }

    public Map<String, Object> getDataParams() {
        return dataParams == null ? null : new LinkedHashMap<>(dataParams);
    }

    public void setDataParams(Map<String, Object> dataParams) {
        this.dataParams = dataParams == null ? null : new LinkedHashMap<>(dataParams);
    }

    /**
     * Меняет приоритет и вместе с ним ранг для сортировки.
     *
     * @throws IllegalArgumentException если приоритет не передан: у заявки он есть всегда
     */
    public void setPriority(RequestPriority priority) {
        if (priority == null) {
            throw new IllegalArgumentException("Приоритет заявки обязателен");
        }
        this.priority = priority;
        this.priorityRank = priority.getRank();
    }

    public void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Request.class, getId());
        }
    }

    /**
     * Заявка просрочена сейчас: истёк срок, который для её статуса ещё считается. Срок первого
     * ответа — пока заявка в {@link RequestStatus#NEW}, срок решения — пока она не завершена.
     *
     * <p>Признаки просрочки при этом остаются и у закрытых заявок: это факт о сроке, по нему
     * считается доля закрытых в срок (T-094). Так же это правило записано в фильтре реестра
     * ({@code RequestSpecifications.overdue}) — менять только вместе.
     */
    public boolean isOverdue() {
        return firstResponseOverdue && status == RequestStatus.NEW
                || resolutionOverdue && !status.isTerminal();
    }

    /**
     * Ни один срок не  нарушен. Признаки остаються и после закрытия.*/
    public boolean metDeadlines() {
        return !firstResponseOverdue && !resolutionOverdue;
    }

    /**
     * Переносит сроки, пересчитанные по новой политике (T-062), и снимает признак просрочки срока,
     * который после переноса ещё не наступил (#200): иначе заявка осталась бы «просроченной» по
     * сроку, который ещё впереди, а его настоящее нарушение не эскалировалось бы.
     *
     * <p>Срок переносится, только пока он ещё считается — по тому же правилу, что в
     * {@link #isOverdue()}: срок первого ответа — пока заявка в {@link RequestStatus#NEW}, срок
     * решения — пока она не завершена. Сыгравший срок остаётся как был: его признак — факт о
     * том, как на заявку ответили.
     *
     * @param now момент переноса: срок позже него ещё не наступил
     * @return по каким срокам снят признак просрочки — у них надо снять и отметку эскалации
     */
    public ReopenedDeadlines moveDeadlines(Instant firstResponse, Instant resolution,
            Instant now) {
        boolean firstResponseReopened = false;
        if (status == RequestStatus.NEW) {
            firstResponseDueAt = firstResponse;
            firstResponseReopened = firstResponseOverdue && firstResponse.isAfter(now);
            firstResponseOverdue = firstResponseOverdue && !firstResponseReopened;
        }
        boolean resolutionReopened = false;
        if (!status.isTerminal()) {
            resolutionDueAt = resolution;
            resolutionReopened = resolutionOverdue && resolution.isAfter(now);
            resolutionOverdue = resolutionOverdue && !resolutionReopened;
        }
        return new ReopenedDeadlines(firstResponseReopened, resolutionReopened);
    }

    /**
     * Сколько заявка ждет назначения исполнителя: от создания до {@code now}. {@code null}, если
     * исполнитель уже есть - тогда ждать не чего. */
    public Duration assignmentWait(Instant now) {
        if (assigneeId != null || getCreatedAt() == null || now == null) {
            return null;
        }
        return Duration.between(getCreatedAt().toInstant(), now);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Request request)) {
            return false;
        }
        return getId() != null && getId().equals(request.getId());
    }

    @Override
    public int hashCode() {
        return Request.class.hashCode();
    }
}

package ru.practicum.crm.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Заявка на обработку данных — главная сущность продукта (ТЗ §4.2).
 *
 * <p>Состав полей зафиксирован в T-033 (#34). Правила переходов статусов (T-043),
 * выбор приоритета новой заявки ({@link RequestPriority#resolve}) и расчёт сроков по SLA (T-049)
 * находятся за пределами сущности: здесь только хранение состояния.
 */
@Entity
@Table(name = "requests")
@Getter
public class Request extends TenantScopedEntity {

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

    @Setter
    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Setter
    @Column(name = "desired_due_at")
    private Instant desiredDueAt;

    @Setter
    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    @Setter
    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Setter
    @Column(name = "overdue", nullable = false)
    private boolean overdue;

    @Setter
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

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
     * исполнитель — при назначении.
     */
    public Request(UUID tenantId, UUID authorId, String subject, String description,
                   RequestStatus status) {
        super(tenantId);
        this.authorId = authorId;
        this.subject = subject;
        this.description = description;
        this.status = status;
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

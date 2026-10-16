package ru.practicum.crm.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Запись истории статусов: из какого статуса в какой перевели заявку, кто и с каким комментарием
 * (T-044, ТЗ §5.2). Когда — время создания записи ({@link #getCreatedAt()}): она делается в той же
 * транзакции, что и сама смена статуса.
 *
 * <p>Запись только добавляется: сеттеров нет, колонки не обновляются, а база отклоняет изменение
 * и удаление триггером {@code request_status_history_read_only}.
 */
@Entity
@Table(name = "request_status_history")
@Getter
public class RequestStatusChange extends TenantScopedEntity {

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private RequestStatus toStatus;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    /** Комментарий к переходу; при отклонении — его причина. */
    @Column(name = "comment", updatable = false)
    private String comment;

    protected RequestStatusChange() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public RequestStatusChange(UUID tenantId, UUID requestId, RequestStatus fromStatus,
            RequestStatus toStatus, UUID authorId, String comment) {
        super(tenantId);
        this.requestId = requestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.authorId = authorId;
        this.comment = comment;
    }
}

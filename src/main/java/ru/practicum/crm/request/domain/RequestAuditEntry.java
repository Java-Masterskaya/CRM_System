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
 * Запись журнала аудита: какое значимое поле заявки изменилось, что было, что стало и кто
 * изменил. Когда — время создания записи ({@link #getCreatedAt()}): она делается в той же
 * транзакции, что и само изменение.
 *
 * <p>Запись только добавляется: сеттеров нет, колонки не обновляются, а база отклоняет изменение
 * и удаление триггером {@code request_audit_log_read_only}.
 */
@Entity
@Table(name = "request_audit_log")
@Getter
public class RequestAuditEntry extends TenantScopedEntity {

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, updatable = false, length = 50)
    private AuditedField field;

    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    protected RequestAuditEntry() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public RequestAuditEntry(UUID tenantId, UUID requestId, FieldChange change, UUID authorId) {
        super(tenantId);
        this.requestId = requestId;
        this.field = change.field();
        this.oldValue = change.oldValue();
        this.newValue = change.newValue();
        this.authorId = authorId;
    }
}

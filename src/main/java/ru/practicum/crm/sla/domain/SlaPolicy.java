package ru.practicum.crm.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Политика сроков (T-055, ТЗ §6.4): срок первого ответа и срок решения.
 *
 * <p>Политика задаётся либо для пары «тип заявки + приоритет», либо как политика по умолчанию —
 * тогда тип и приоритет оба пусты. Чему относится политика, после создания не меняется; менять
 * можно только сроки ({@link #changeTerms}).
 */
@Entity
@Table(name = "sla_policies")
@Getter
public class SlaPolicy extends TenantScopedEntity {

    @Column(name = "type_id", updatable = false)
    private UUID typeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", updatable = false, length = 20)
    private RequestPriority priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    protected SlaPolicy() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует методы ниже.
    }

    private SlaPolicy(UUID tenantId, UUID typeId, RequestPriority priority, SlaTerms terms) {
        super(tenantId);
        this.typeId = typeId;
        this.priority = priority;
        this.firstResponseMinutes = terms.firstResponseMinutes();
        this.resolutionMinutes = terms.resolutionMinutes();
    }

    /** Политика для пары «тип заявки + приоритет». */
    public static SlaPolicy forPair(UUID tenantId, UUID typeId, RequestPriority priority,
            SlaTerms terms) {
        return new SlaPolicy(tenantId, typeId, priority, terms);
    }

    /** Политика по умолчанию: применяется, когда для пары своей политики нет. */
    public static SlaPolicy byDefault(UUID tenantId, SlaTerms terms) {
        return new SlaPolicy(tenantId, null, null, terms);
    }

    public boolean isDefault() {
        return typeId == null;
    }

    public SlaTerms getTerms() {
        return new SlaTerms(firstResponseMinutes, resolutionMinutes);
    }

    public void changeTerms(SlaTerms terms) {
        this.firstResponseMinutes = terms.firstResponseMinutes();
        this.resolutionMinutes = terms.resolutionMinutes();
    }
}

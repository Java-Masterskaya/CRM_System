package ru.practicum.crm.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * Тип заявки — категория обращения, принадлежащая арендатору (ТЗ §4.1).
 *
 * <p>Справочник настраивается администратором арендатора; стартовых значений нет, поэтому
 * до создания первого типа клиент подать заявку не может. Приоритет по умолчанию может быть
 * не задан — тогда новая заявка получает {@link RequestPriority#FALLBACK}.
 *
 * <p>Тип не удаляется, а отключается: на него ссылаются ранее созданные заявки, и они
 * обязаны читаться после отключения.
 */
@Entity
@Table(name = "request_types")
@Getter
public class RequestType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_priority", length = 20)
    private RequestPriority defaultPriority;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RequestType() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public RequestType(UUID tenantId, String name) {
        this.tenantId = tenantId;
        this.name = name;
    }

    /**
     * Меняет описание типа: название, пояснение и приоритет по умолчанию.
     *
     * <p>Одним методом, а не тремя сеттерами: администратор правит карточку типа целиком,
     * и частичное изменение половины полей смысла не имеет.
     */
    public void describe(String name, String description, RequestPriority defaultPriority) {
        this.name = name;
        this.description = description;
        this.defaultPriority = defaultPriority;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RequestType requestType)) {
            return false;
        }
        return id != null && id.equals(requestType.id);
    }

    @Override
    public int hashCode() {
        return RequestType.class.hashCode();
    }
}

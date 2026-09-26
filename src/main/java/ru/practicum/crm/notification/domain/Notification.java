package ru.practicum.crm.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.TenantScopedEntity;
import ru.practicum.crm.notification.api.NotificationType;

/**
 * Уведомление: кому и о чём отправлялось письмо и чем закончилась последняя попытка.
 *
 * <p>Одно событие очереди — одно уведомление: строка создаётся при первой попытке доставки и
 * обновляется при каждой следующей, её состояние отражает последнюю попытку. Адресат и тип после
 * создания не меняются.
 */
@Entity
@Table(name = "notifications")
@Getter
public class Notification extends TenantScopedEntity {

    @Column(name = "outbox_event_id", updatable = false)
    private UUID outboxEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 50)
    private NotificationType type;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public Notification(UUID tenantId, UUID outboxEventId, NotificationType type,
            String recipientEmail) {
        super(tenantId);
        this.outboxEventId = outboxEventId;
        this.type = type;
        this.recipientEmail = recipientEmail;
    }

    /** Попытка номер {@code attempt} удалась: письмо принял почтовый сервер. */
    public void markSent(int attempt, Instant at) {
        if (isOutdated(attempt)) {
            return;
        }
        attempts = attempt;
        status = NotificationStatus.SENT;
        sentAt = at;
        lastErrorCode = null;
    }

    /** Попытка номер {@code attempt} не удалась по причине {@code errorCode}. */
    public void markFailed(int attempt, String errorCode) {
        if (isOutdated(attempt)) {
            return;
        }
        attempts = attempt;
        status = NotificationStatus.FAILED;
        lastErrorCode = errorCode;
    }

    /**
     * Результат старее уже записанного: обработчик, у которого истёк срок аренды события,
     * закончил позже того, кто событие перехватил. Более свежий результат не затирается.
     */
    private boolean isOutdated(int attempt) {
        return attempt < attempts;
    }
}

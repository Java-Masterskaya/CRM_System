package ru.practicum.crm.notification.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.notification.domain.Notification;

/**
 * Доступ к уведомлениям. Как и в остальных репозиториях, операции объявлены поимённо, а чтение
 * всегда с арендатором.
 */
public interface NotificationRepository extends Repository<Notification, UUID> {

    <S extends Notification> S save(S notification);

    /** Уведомление по событию очереди — чтобы повторная попытка обновила ту же строку. */
    Optional<Notification> findByTenantIdAndOutboxEventId(UUID tenantId, UUID outboxEventId);
}

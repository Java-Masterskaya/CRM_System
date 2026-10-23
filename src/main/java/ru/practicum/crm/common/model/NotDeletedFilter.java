package ru.practicum.crm.common.model;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/**
 * Hibernate filter мягкое удаление (T-047): скрывает записи с {@code deleted = true}.
 *
 * <p>Включается вместе с фильтром арендатора в {@code TenantPersistence}: так удаленные заявки
 * не нужно отсекать в каждом запросе.
 * */
public final class NotDeletedFilter {

    public static final String NAME = "notDeleteFilter";

    private NotDeletedFilter() {
    }

    /**
     * Временно снимаем скрытие удаленных - чтобы найти запись для восстановления.
     * */
    public static void disable(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);
        if (session.getEnabledFilter(NAME) != null) {
            session.disableFilter(NAME);
        }
    }

    public static void enable(EntityManager entityManager) {
        entityManager.unwrap(Session.class).enableFilter(NAME);
    }
}

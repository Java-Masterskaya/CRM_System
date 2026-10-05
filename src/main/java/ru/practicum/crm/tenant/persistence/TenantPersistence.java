package ru.practicum.crm.tenant.persistence;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.Session;
import ru.practicum.crm.common.model.TenantIds;

public class TenantPersistence {

    public TenantPersistence() {
    }

    public static void bind(EntityManager entityManager, UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        if (tenantId == null) {
            if (session.getEnabledFilter(TenantIds.FILTER_NAME) != null) {
                session.disableFilter(TenantIds.FILTER_NAME);
            }
        } else {
            session.enableFilter(TenantIds.FILTER_NAME)
                    .setParameter(TenantIds.PARAM, tenantId);
        }
        session.doWork(connection -> writeTenantSettings(connection, tenantId));
    }

    public static void writeTenantSettings(Connection connection, UUID tenantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }
    }
}

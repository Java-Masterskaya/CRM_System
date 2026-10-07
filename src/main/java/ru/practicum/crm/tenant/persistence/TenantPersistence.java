package ru.practicum.crm.tenant.persistence;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.hibernate.Session;
import ru.practicum.crm.common.model.TenantIds;

public class TenantPersistence {

    private static final String UNDEFINED_OBJECT = "42704";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

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
        assumeAppRole(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }
    }

    private static void assumeAppRole(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE crm_app");
        } catch (SQLException exception) {
            String state = exception.getSQLState();
            if (!INSUFFICIENT_PRIVILEGE.equals(state) && !UNDEFINED_OBJECT.equals(state)) {
                throw exception;
            }
        }
    }
}

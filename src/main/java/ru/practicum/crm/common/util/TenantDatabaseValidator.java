package ru.practicum.crm.common.util;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.TenantIsolationInitializationException;

@Component
@Log4j2
@RequiredArgsConstructor
public class TenantDatabaseValidator {

    private final DataSource dataSource;

    @PostConstruct
    public void validateRlsRoleConfig() {
        String sql =
                """
                SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') AS role_exists,
                pg_has_role(current_user, 'crm_app', 'MEMBER') AS has_membership
                """;
        try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {

            if (resultSet.next()) {
                boolean roleExists = resultSet.getBoolean("role_exists");
                boolean hasMembership = resultSet.getBoolean("has_membership");

                if (!roleExists) {
                    throw new TenantIsolationInitializationException(
                            "Критическая ошибка! Убедитесь что миграции RLS были применены."
                    );
                }

                if (!hasMembership) {
                    throw new TenantIsolationInitializationException(
                            "Критическая ошибка! "
                                    + "Текущий пользователь не яляется членом роли 'crm_app'"
                    );
                }

                log.info("Проверка прав RLS успешно пройдено.");
            }
        } catch (SQLException exception) {
            throw new TenantIsolationInitializationException(
                    "Не удалось выполнить проверку ролей RLS в базе данных.");
        }
    }
}

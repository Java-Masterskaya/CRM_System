package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PermissionRepositoryIT {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM role_permissions");
        jdbcTemplate.execute("DELETE FROM roles");
        jdbcTemplate.execute("DELETE FROM permissions");
    }

    @Test
    @DisplayName("Должен возвращать объединение прав всех ролей пользователя без дублей")
    void shouldFindUnionOfPermissionCodesByUserId() {
        UUID userId = UUID.randomUUID();

        UUID operatorRoleId = UUID.randomUUID();
        UUID adminRoleId = UUID.randomUUID();

        UUID statusChangePermissionId = UUID.randomUUID();
        UUID userManagePermissionId = UUID.randomUUID();
        UUID userReadPermissionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO permissions (id, code) VALUES (?, ?)",
                statusChangePermissionId,
                "REQUEST_STATUS_CHANGE"
        );
        jdbcTemplate.update(
                "INSERT INTO permissions (id, code) VALUES (?, ?)",
                userManagePermissionId,
                "USER_MANAGE"
        );
        jdbcTemplate.update(
                "INSERT INTO permissions (id, code) VALUES (?, ?)",
                userReadPermissionId,
                "USER_READ"
        );

        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name) VALUES (?, ?, ?)",
                operatorRoleId,
                "OPERATOR",
                "Operator"
        );
        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name) VALUES (?, ?, ?)",
                adminRoleId,
                "ADMIN",
                "Admin"
        );

        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
                userId,
                operatorRoleId
        );
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
                userId,
                adminRoleId
        );

        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                operatorRoleId,
                statusChangePermissionId
        );
        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                operatorRoleId,
                userManagePermissionId
        );
        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                adminRoleId,
                userManagePermissionId
        );
        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                adminRoleId,
                userReadPermissionId
        );

        Set<String> permissionCodes =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissionCodes)
                .containsExactlyInAnyOrder(
                        "REQUEST_STATUS_CHANGE",
                        "USER_MANAGE",
                        "USER_READ"
                );
        assertThat(permissionCodes).hasSize(3);
    }

    @Test
    @DisplayName("Пользователь без ролей не должен получать права")
    void shouldReturnEmptySetWhenUserHasNoRoles() {
        UUID userId = UUID.randomUUID();

        UUID permissionId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO permissions (id, code) VALUES (?, ?)",
                permissionId,
                "USER_MANAGE"
        );

        jdbcTemplate.update(
                "INSERT INTO roles (id, code, name) VALUES (?, ?, ?)",
                roleId,
                "ADMIN",
                "Admin"
        );

        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId,
                permissionId
        );

        Set<String> permissionCodes =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissionCodes).isEmpty();
    }
}

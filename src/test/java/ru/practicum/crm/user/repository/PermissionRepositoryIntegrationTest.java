package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;

class PermissionRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAllowSamePermissionCodeForDifferentTenants() {
        UUID firstTenantId = createTenant();
        UUID secondTenantId = createTenant();

        insertPermission(firstTenantId, "USER_MANAGE");
        insertPermission(secondTenantId, "USER_MANAGE");

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM permissions
                WHERE code = 'USER_MANAGE'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldAllowSameRoleCodeForDifferentTenants() {
        UUID firstTenantId = createTenant();
        UUID secondTenantId = createTenant();

        insertRole(firstTenantId, "ADMIN", "Administrator");
        insertRole(secondTenantId, "ADMIN", "Administrator");

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM roles
                WHERE code = 'ADMIN'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldFindUnionOfPermissionCodesByUserId() {
        UUID tenantId = createTenant();

        UUID operatorRoleId = insertRole(
                tenantId,
                "OPERATOR",
                "Operator"
        );
        UUID adminRoleId = insertRole(
                tenantId,
                "ADMIN",
                "Administrator"
        );

        UUID statusChangePermissionId = insertPermission(
                tenantId,
                "REQUEST_STATUS_CHANGE"
        );
        UUID userManagePermissionId = insertPermission(
                tenantId,
                "USER_MANAGE"
        );

        UUID userId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO user_roles (user_id, role_id)
                VALUES (?, ?)
                """,
                userId,
                operatorRoleId
        );

        jdbcTemplate.update(
                """
                INSERT INTO user_roles (user_id, role_id)
                VALUES (?, ?)
                """,
                userId,
                adminRoleId
        );

        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                """,
                operatorRoleId,
                statusChangePermissionId
        );

        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                """,
                operatorRoleId,
                userManagePermissionId
        );

        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (role_id, permission_id)
                VALUES (?, ?)
                """,
                adminRoleId,
                userManagePermissionId
        );

        Set<String> permissions =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissions)
                .containsExactlyInAnyOrder(
                        "REQUEST_STATUS_CHANGE",
                        "USER_MANAGE"
                );
    }

    @Test
    void shouldReturnEmptySetWhenUserHasNoRoles() {
        UUID userId = UUID.randomUUID();

        Set<String> permissions =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissions).isEmpty();
    }

    private UUID createTenant() {
        UUID tenantId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (
                    id,
                    name,
                    active,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, true, NOW(), NOW())
                """,
                tenantId,
                "Test tenant " + tenantId
        );

        return tenantId;
    }

    private UUID insertRole(
            UUID tenantId,
            String code,
            String name
    ) {
        UUID roleId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO roles (
                    id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, ?, ?)
                """,
                roleId,
                tenantId,
                code,
                name
        );

        return roleId;
    }

    private UUID insertPermission(
            UUID tenantId,
            String code
    ) {
        UUID permissionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO permissions (
                    id,
                    tenant_id,
                    code
                )
                VALUES (?, ?, ?)
                """,
                permissionId,
                tenantId,
                code
        );

        return permissionId;
    }
}

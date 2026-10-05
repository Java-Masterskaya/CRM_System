package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;

class PermissionRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insertPermission_whenDifferentTenantsUseSameCode_isAllowed() {
        UUID firstTenantId = createTenant();
        UUID secondTenantId = createTenant();

        insertPermission(firstTenantId, "CUSTOM_PERMISSION");
        insertPermission(secondTenantId, "CUSTOM_PERMISSION");

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM permissions
                WHERE code = 'CUSTOM_PERMISSION'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void insertRole_whenDifferentTenantsUseSameCode_isAllowed() {
        UUID firstTenantId = createTenant();
        UUID secondTenantId = createTenant();

        insertRole(firstTenantId, "CUSTOM_ROLE", "Custom role");
        insertRole(secondTenantId, "CUSTOM_ROLE", "Custom role");

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM roles
                WHERE code = 'CUSTOM_ROLE'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void findAllPermissionCodesByUserId_whenUserHasMultipleRoles_returnsUnionOfPermissions() {
        UUID tenantId = createTenant();

        UUID operatorRoleId = insertRole(tenantId, "TEST_OPERATOR", "Test operator");
        UUID adminRoleId = insertRole(tenantId, "TEST_ADMIN", "Test administrator");

        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId);

        jdbcTemplate.update(
                "INSERT INTO user_roles (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                tenantId,
                userId,
                operatorRoleId
        );
        jdbcTemplate.update(
                "INSERT INTO user_roles (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                tenantId,
                userId,
                adminRoleId
        );

        grantPermission(tenantId, operatorRoleId,
                insertPermission(tenantId, "TEST_STATUS_CHANGE"));
        UUID userManagePermissionId = insertPermission(tenantId, "TEST_USER_MANAGE");
        grantPermission(tenantId, operatorRoleId, userManagePermissionId);
        grantPermission(tenantId, adminRoleId, userManagePermissionId);

        Set<String> permissions =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissions)
                .containsExactlyInAnyOrder(
                        "TEST_STATUS_CHANGE",
                        "TEST_USER_MANAGE"
                );
    }

    @Test
    void findAllPermissionCodesByUserId_whenUserHasNoRoles_returnsEmptySet() {
        UUID userId = UUID.randomUUID();

        Set<String> permissions =
                permissionRepository.findAllPermissionCodesByUserId(userId);

        assertThat(permissions).isEmpty();
    }

    @Test
    void insertRolePermission_whenPermissionBelongsToAnotherTenant_isRejected() {
        UUID roleTenantId = createTenant();
        UUID permissionTenantId = createTenant();
        UUID roleId = insertRole(roleTenantId, "CROSS_ROLE", "Cross tenant role");
        UUID permissionId = insertPermission(permissionTenantId, "CROSS_PERMISSION");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO role_permissions (tenant_id, role_id, permission_id) "
                        + "VALUES (?, ?, ?)",
                roleTenantId,
                roleId,
                permissionId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertUserRole_whenUserAndRoleBelongToDifferentTenants_isRejected() {
        UUID userTenantId = createTenant();
        UUID roleTenantId = createTenant();
        UUID userId = UUID.randomUUID();
        UUID roleId = insertRole(roleTenantId, "CROSS_ROLE", "Cross tenant role");
        insertUser(userTenantId, userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_roles (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                userTenantId,
                userId,
                roleId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createTenant() {
        UUID tenantId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, active, created_at, updated_at)
                VALUES (?, ?, ?, true, NOW(), NOW())
                """,
                tenantId,
                "Test tenant " + tenantId,
                "test-tenant-" + tenantId
        );

        return tenantId;
    }

    private UUID insertRole(UUID tenantId, String code, String name) {
        UUID roleId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO roles (id, tenant_id, code, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                roleId,
                tenantId,
                code,
                name
        );

        return roleId;
    }

    private UUID insertPermission(UUID tenantId, String code) {
        UUID permissionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO permissions (id, tenant_id, code, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                """,
                permissionId,
                tenantId,
                code
        );

        return permissionId;
    }

    private void insertUser(UUID tenantId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, status, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 'test-hash', 'ACTIVE', NOW(), NOW())",
                userId,
                tenantId,
                userId + "@example.test"
        );
    }

    private void grantPermission(UUID tenantId, UUID roleId, UUID permissionId) {
        jdbcTemplate.update(
                "INSERT INTO role_permissions (tenant_id, role_id, permission_id) "
                        + "VALUES (?, ?, ?)",
                tenantId,
                roleId,
                permissionId
        );
    }
}

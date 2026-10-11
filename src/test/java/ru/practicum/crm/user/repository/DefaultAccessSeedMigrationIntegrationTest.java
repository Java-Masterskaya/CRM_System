package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;

class DefaultAccessSeedMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void givenTenantCreatedBeforeT021_whenMigrationRuns_thenSeedsAccessDefaults() {
        removeT021ObjectsAndHistory();
        UUID existingTenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID systemTenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                existingTenantId,
                "Existing tenant before T-021",
                "existing-tenant-before-t-021"
        );
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                otherTenantId,
                "Other existing tenant before T-021",
                "other-existing-tenant-before-t-021"
        );
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                systemTenantId,
                "System",
                "system"
        );

        UUID localRoleId = UUID.randomUUID();
        UUID foreignRoleId = UUID.randomUUID();
        UUID foreignPermissionId = UUID.randomUUID();
        UUID localUserId = UUID.randomUUID();
        UUID orphanUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO roles (id, tenant_id, code, name, created_at, updated_at) "
                        + "VALUES (?, ?, 'LEGACY_LOCAL', 'Legacy local', NOW(), NOW()), "
                        + "(?, ?, 'LEGACY_FOREIGN', 'Legacy foreign', NOW(), NOW())",
                localRoleId,
                existingTenantId,
                foreignRoleId,
                otherTenantId
        );
        jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, created_at, updated_at) "
                        + "VALUES (?, ?, 'LEGACY_FOREIGN_PERMISSION', NOW(), NOW())",
                foreignPermissionId,
                otherTenantId
        );
        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                localRoleId,
                foreignPermissionId
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, status, created_at, "
                        + "updated_at) VALUES (?, ?, 'legacy@example.test', 'hash', 'ACTIVE', "
                        + "NOW(), NOW())",
                localUserId,
                existingTenantId
        );
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?), (?, ?), (?, ?)",
                localUserId,
                localRoleId,
                localUserId,
                foreignRoleId,
                orphanUserId,
                localRoleId
        );

        flyway.migrate();

        assertThat(count("SELECT COUNT(*) FROM roles WHERE tenant_id = ?", existingTenantId))
                .isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM permissions WHERE tenant_id = ?", existingTenantId))
                .isEqualTo(25);
        assertThat(count(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.tenant_id = ? AND r.code = 'ADMIN' "
                        + "AND p.code = 'ANALYTICS_READ'",
                existingTenantId
        )).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM roles WHERE tenant_id = ?", systemTenantId))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM permissions WHERE tenant_id = ?", systemTenantId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM tenants WHERE id = ?",
                Boolean.class,
                systemTenantId
        )).isFalse();
        assertThat(count("SELECT COUNT(*) FROM role_permissions WHERE role_id = ?",
                localRoleId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_id = ?",
                localUserId, foreignRoleId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_roles WHERE user_id = ?", orphanUserId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM user_roles WHERE user_id = ? AND role_id = ?",
                UUID.class,
                localUserId,
                localRoleId
        )).isEqualTo(existingTenantId);
    }

    private void removeT021ObjectsAndHistory() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_seed_tenant_access_defaults ON tenants");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS seed_new_tenant_access_defaults()");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS seed_tenant_access_defaults(UUID)");
        jdbcTemplate.execute("ALTER TABLE role_permissions DROP CONSTRAINT IF EXISTS "
                + "fk_role_permissions_role");
        jdbcTemplate.execute("ALTER TABLE role_permissions DROP CONSTRAINT IF EXISTS "
                + "fk_role_permissions_permission");
        jdbcTemplate.execute("ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS fk_user_roles_role");
        jdbcTemplate.execute("ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS fk_user_roles_user");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_role_permissions_tenant_permission");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_user_roles_tenant_role");
        jdbcTemplate.execute(
                "ALTER TABLE role_permissions DROP COLUMN IF EXISTS tenant_id CASCADE");
        jdbcTemplate.execute("ALTER TABLE user_roles DROP COLUMN IF EXISTS tenant_id CASCADE");
        jdbcTemplate.execute("ALTER TABLE roles DROP CONSTRAINT IF EXISTS uk_roles_tenant_id");
        jdbcTemplate.execute("ALTER TABLE permissions DROP CONSTRAINT IF EXISTS "
                + "uk_permissions_tenant_id");
        jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_tenant_id");
        jdbcTemplate.execute("ALTER TABLE role_permissions ADD CONSTRAINT "
                + "fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) "
                + "ON DELETE CASCADE");
        jdbcTemplate.execute("ALTER TABLE role_permissions ADD CONSTRAINT "
                + "fk_role_permissions_permission FOREIGN KEY (permission_id) "
                + "REFERENCES permissions (id) ON DELETE CASCADE");
        jdbcTemplate.execute("ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_role "
                + "FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE");
        jdbcTemplate.update(
                "DELETE FROM flyway_schema_history WHERE version = ?",
                "202610021000"
        );
        jdbcTemplate.update(
                "DELETE FROM flyway_schema_history WHERE version = ?",
                "202610011900"
        );
    }

    private int count(String sql, UUID tenantId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, tenantId));
    }

    private int count(String sql, UUID firstId, UUID secondId) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(sql, Integer.class, firstId, secondId)
        );
    }
}

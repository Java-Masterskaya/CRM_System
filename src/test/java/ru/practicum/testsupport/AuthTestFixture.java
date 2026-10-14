package ru.practicum.testsupport;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
@RequiredArgsConstructor
public class AuthTestFixture {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public TestTenant createTenant(String slug) {
        return createTenant(slug, true);
    }

    public TestTenant createTenant(String slug, boolean active) {
        UUID tenantId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                        INSERT INTO tenants (
                            id, name, slug, active, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                tenantId, "Integration test tenant", slug, active
        );

        return new TestTenant(tenantId, slug);
    }

    public TestUser createUser(TestTenant tenant, String email, String password) {
        UUID userId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(password);

        jdbcTemplate.update(
                """
                        INSERT INTO users (
                            id, tenant_id, email, password_hash, status,
                            deleted_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'ACTIVE', NULL, NOW(), NOW())
                """,
                userId, tenant.id(), email, passwordHash
        );

        return new TestUser(userId);
    }

    public void blockUser(UUID tenantId, UUID userId) {
        jdbcTemplate.update(
                """
                        UPDATE users
                        SET status = 'BLOCKED', updated_at = NOW()
                        WHERE id = ? AND tenant_id = ?
                """,
                userId, tenantId
        );
    }

    public void deleteUser(UUID tenantId, UUID userId) {
        jdbcTemplate.update(
                """
                        UPDATE users
                        SET deleted_at = NOW(), updated_at = NOW()
                        WHERE id = ? AND tenant_id = ?
                """,
                userId, tenantId
        );
    }

    public void grantPermission(
            TestTenant tenant,
            TestUser user,
            String permissionCode
    ) {
        UUID roleId = UUID.randomUUID();
        UUID permissionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                        INSERT INTO roles (
                            id, tenant_id, code, name, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                roleId, tenant.id(), "AUTH_TEST_ROLE", "Authentication test role"
        );

        jdbcTemplate.update(
                """
                        INSERT INTO permissions (
                            id, tenant_id, code, created_at, updated_at
                        ) VALUES (?, ?, ?, NOW(), NOW())
                """,
                permissionId, tenant.id(), permissionCode
        );

        jdbcTemplate.update(
                """
                        INSERT INTO user_roles (tenant_id, user_id, role_id)
                        VALUES (?, ?, ?)
                """,
                tenant.id(), user.id(), roleId
        );

        jdbcTemplate.update(
                """
                        INSERT INTO role_permissions (tenant_id, role_id, permission_id)
                        VALUES (?, ?, ?)
                """,
                tenant.id(), roleId, permissionId
        );
    }

    public record TestTenant(UUID id, String slug) {
    }

    public record TestUser(UUID id) {
    }
}

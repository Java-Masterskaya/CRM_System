package ru.practicum.testsupport;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;

@TestConfiguration
@RequiredArgsConstructor
public class AuthTestFixture {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public TestTenant createTenant(String slug) {
        return createTenant(slug, true);
    }

    public TestTenant createTenant(String slug, boolean active) {
        Tenant tenant = tenantRepository.save(
                new Tenant(
                        "Integration test tenant",
                        slug,
                        active
                )
        );

        return new TestTenant(
                tenant.getId(),
                tenant.getSlug()
        );
    }

    public TestUser createUser(
            TestTenant tenant,
            String email,
            String password
    ) {
        UserEntity user = userRepository.save(
                new UserEntity(
                        tenant.id(),
                        email,
                        passwordEncoder.encode(password),
                        UserStatus.ACTIVE
                )
        );

        return new TestUser(user.getId());
    }

    public void blockUser(UUID tenantId, UUID userId) {
        UserEntity user = userRepository.findById(userId, tenantId)
                .orElseThrow();

        user.block();
        userRepository.save(user);
    }

    public void deleteUser(UUID tenantId, UUID userId) {
        UserEntity user = userRepository.findById(userId, tenantId)
                .orElseThrow();

        user.delete();
        userRepository.save(user);
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
                    id,
                    tenant_id,
                    code,
                    name,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                roleId,
                tenant.id(),
                "AUTH_TEST_ROLE",
                "Authentication test role"
        );

        jdbcTemplate.update(
                """
                INSERT INTO permissions (
                    id,
                    tenant_id,
                    code,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, NOW(), NOW())
                """,
                permissionId,
                tenant.id(),
                permissionCode
        );

        jdbcTemplate.update(
                """
                INSERT INTO user_roles (
                    tenant_id,
                    user_id,
                    role_id
                )
                VALUES (?, ?, ?)
                """,
                tenant.id(),
                user.id(),
                roleId
        );

        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (
                    tenant_id,
                    role_id,
                    permission_id
                )
                VALUES (?, ?, ?)
                """,
                tenant.id(),
                roleId,
                permissionId
        );
    }

    public record TestTenant(UUID id, String slug) {
    }

    public record TestUser(UUID id) {
    }
}

package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;
import ru.practicum.crm.user.domain.PermissionCode;

class DefaultAccessSeedIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantAccessSeeder tenantAccessSeeder;

    @Test
    void creatingTenant_seedsRolesAndPermissionsWithSafeRoleAssignments() {
        UUID tenantId = createTenant();

        assertThat(codes("SELECT code FROM roles WHERE tenant_id = ?", tenantId))
                .containsExactlyInAnyOrder("CLIENT", "OPERATOR", "ADMIN");
        assertThat(codes("SELECT code FROM permissions WHERE tenant_id = ?", tenantId))
                .hasSize(25)
                .contains("REQUEST_READ_OWN", "REQUEST_READ_ALL", "REQUEST_STATUS_CHANGE",
                        "USER_MANAGE", "TENANT_SETTINGS_MANAGE", "ANALYTICS_READ")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(PermissionCode.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet()));

        Map<String, Set<String>> grants = Map.of(
                "CLIENT", grantsFor(tenantId, "CLIENT"),
                "OPERATOR", grantsFor(tenantId, "OPERATOR"),
                "ADMIN", grantsFor(tenantId, "ADMIN")
        );

        assertThat(grants.get("CLIENT"))
                .contains("REQUEST_READ_OWN", "REQUEST_CREATE")
                .doesNotContain("REQUEST_READ_ALL", "REQUEST_STATUS_CHANGE", "USER_MANAGE");
        assertThat(grants.get("OPERATOR"))
                .contains("REQUEST_READ_ALL", "REQUEST_STATUS_CHANGE")
                .doesNotContain("USER_MANAGE", "TENANT_SETTINGS_MANAGE");
        assertThat(grants.get("ADMIN"))
                .contains("USER_MANAGE", "TENANT_SETTINGS_MANAGE", "REQUEST_STATUS_CHANGE",
                        "ANALYTICS_READ")
                .doesNotContain("REQUEST_CREATE", "REQUEST_READ_OWN", "REQUEST_CANCEL_OWN");
    }

    @Test
    void seedingTenantAccessDefaults_twice_doesNotCreateDuplicates() {
        UUID tenantId = createTenant();
        int[] originalCounts = countsFor(tenantId);

        tenantAccessSeeder.seedDefaults(tenantId);
        tenantAccessSeeder.seedDefaults(tenantId);

        assertThat(countsFor(tenantId)).containsExactly(originalCounts);
    }

    private UUID createTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, active, created_at, updated_at) "
                        + "VALUES (?, ?, true, NOW(), NOW())",
                tenantId,
                "Access seed integration tenant"
        );
        tenantAccessSeeder.seedDefaults(tenantId);
        return tenantId;
    }

    private Set<String> codes(String sql, UUID tenantId) {
        return Set.copyOf(jdbcTemplate.queryForList(sql, String.class, tenantId));
    }

    private Set<String> grantsFor(UUID tenantId, String roleCode) {
        return Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT p.code
                FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id AND rp.tenant_id = p.tenant_id
                JOIN roles r ON r.id = rp.role_id AND r.tenant_id = rp.tenant_id
                WHERE p.tenant_id = ? AND r.code = ?
                """,
                String.class,
                tenantId,
                roleCode
        ));
    }

    private int[] countsFor(UUID tenantId) {
        return new int[] {
            count("SELECT COUNT(*) FROM permissions WHERE tenant_id = ?", tenantId),
            count("SELECT COUNT(*) FROM roles WHERE tenant_id = ?", tenantId),
            count(
                    "SELECT COUNT(*) FROM role_permissions rp "
                            + "JOIN roles r ON r.id = rp.role_id AND r.tenant_id = rp.tenant_id "
                            + "WHERE r.tenant_id = ?",
                    tenantId
            )
        };
    }

    private int count(String sql, UUID tenantId) {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(sql, Integer.class, tenantId),
                "COUNT query must return a result"
        );
    }
}

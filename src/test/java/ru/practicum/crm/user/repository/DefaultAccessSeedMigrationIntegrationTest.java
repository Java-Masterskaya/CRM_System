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
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, active, created_at, updated_at) "
                        + "VALUES (?, ?, true, NOW(), NOW())",
                existingTenantId,
                "Existing tenant before T-021"
        );

        flyway.migrate();

        assertThat(count("SELECT COUNT(*) FROM roles WHERE tenant_id = ?", existingTenantId))
                .isEqualTo(3);
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
    }

    private void removeT021ObjectsAndHistory() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_seed_tenant_access_defaults ON tenants");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS seed_new_tenant_access_defaults()");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS seed_tenant_access_defaults(UUID)");
        jdbcTemplate.update(
                "DELETE FROM flyway_schema_history WHERE version = ?",
                "202610011900"
        );
    }

    private int count(String sql, UUID tenantId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, tenantId));
    }
}

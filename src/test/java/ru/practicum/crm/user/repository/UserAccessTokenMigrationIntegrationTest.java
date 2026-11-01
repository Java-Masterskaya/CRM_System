package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.user.api.UserAccessTokenState;

class UserAccessTokenMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private UserAccessTokenState tokenState;

    @AfterEach
    void restoreMigration() {
        flyway.migrate();
    }

    @Test
    void givenLegacyAccounts_whenMigrating_thenRevokesBlockedAndDeletedWithoutRevivingTokens() {
        jdbcTemplate.execute("ALTER TABLE users DROP COLUMN access_token_version");
        jdbcTemplate.update("DELETE FROM flyway_schema_history WHERE version = '202610281800'");
        final UUID tenant = legacyTenant("access-upgrade");
        final UUID otherTenant = legacyTenant("access-upgrade-other");
        final UUID active = legacyUser(tenant, "active@example.com", "ACTIVE", false);
        final UUID blocked = legacyUser(tenant, "blocked@example.com", "BLOCKED", false);
        final UUID deleted = legacyUser(tenant, "deleted@example.com", "ACTIVE", true);
        for (UUID user : new UUID[] {active, blocked, deleted}) {
            legacySession(tenant, user);
        }
        final UUID foreignBlocked = legacyUser(otherTenant, "blocked@example.com",
                "BLOCKED", false);
        legacySession(otherTenant, foreignBlocked);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(tokenState.isRevoked(tenant, active, 0)).isFalse();
        assertThat(tokenState.isRevoked(tenant, active, 1)).isTrue();
        assertThat(tokenState.isRevoked(tenant, blocked, 0)).isTrue();
        assertThat(tokenState.isRevoked(tenant, deleted, 0)).isTrue();
        assertThat(tokenState.isRevoked(otherTenant, foreignBlocked, 0)).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM refresh_tokens"
                        + " WHERE tenant_id = ? AND revoked_at IS NOT NULL",
                UUID.class, otherTenant))
                .containsExactly(foreignBlocked);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM refresh_tokens"
                        + " WHERE tenant_id = ? AND revoked_at IS NOT NULL", UUID.class, tenant))
                .containsExactlyInAnyOrder(blocked, deleted);
        jdbcTemplate.update("UPDATE users SET status = 'ACTIVE', deleted_at = NULL"
                        + " WHERE tenant_id = ?",
                tenant);
        assertThat(tokenState.isRevoked(tenant, blocked, 0)).isTrue();
        assertThat(tokenState.isRevoked(tenant, deleted, 0)).isTrue();
        assertThat(tokenState.isRevoked(tenant, blocked, 1)).isFalse();
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes"
                        + " WHERE schemaname = current_schema() AND indexname = ?",
                String.class, "idx_users_access_revocations"))
                .containsExactly("idx_users_access_revocations");

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(tokenState.isRevoked(tenant, active, 0)).isFalse();
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM refresh_tokens"
                        + " WHERE tenant_id = ? AND revoked_at IS NULL", UUID.class, tenant))
                .containsExactly(active);
    }

    @Test
    void givenMigratedSchema_whenAddingUsers_thenEnforcesDefaultsAndVersionConstraints() {
        UUID tenant = legacyTenant("access-defaults");
        final UUID user = legacyUser(tenant, "new@example.com", "ACTIVE", false);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_token_version FROM users WHERE id = ?",
                Long.class, user)).isEqualTo(0L);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET access_token_version = -1 WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET access_token_version = NULL WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tokenState.isRevoked(tenant, user, 0)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT indexdef FROM pg_indexes"
                        + " WHERE schemaname = current_schema() AND indexname = ?",
                String.class, "idx_users_access_revocations"))
                .contains("INCLUDE", "WHERE", "access_token_version", "BLOCKED",
                        "deleted_at IS NOT NULL")
                .doesNotContain("password_hash");

        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT version FROM flyway_schema_history"
                        + " WHERE version = ? AND success", String.class, "202610281800"))
                .containsExactly("202610281800");
    }

    private UUID legacyTenant(String slug) {
        UUID tenant = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                        + " VALUES (?, 'Обновление доступа', ?, true, now(), now())", tenant, slug);
        return tenant;
    }

    private void legacySession(UUID tenant, UUID user) {
        jdbcTemplate.update("INSERT INTO refresh_tokens (id, family_id, user_id, tenant_id,"
                        + " token_hash, expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, now() + interval '1 day', now())",
                UUID.randomUUID(), UUID.randomUUID(), user, tenant, user.toString());
    }

    private UUID legacyUser(UUID tenant, String email, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, email, password_hash, status,"
                        + " deleted_at, created_at, updated_at) VALUES (?, ?, ?, 'fixture-hash', ?,"
                        + " CASE WHEN ? THEN now() ELSE NULL END, now(), now())",
                id, tenant, email, status, deleted);
        return id;
    }
}

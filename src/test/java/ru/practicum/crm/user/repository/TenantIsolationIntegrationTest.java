package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.testsupport.TenantTestFixture;

@Import(TenantTestFixture.class)
class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void createTenants() {
        tenantA = fixture.createTenant();
        tenantB = fixture.createTenant();
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void list_whenFilterEnabled_containsOnlyCurrentTenant() {
        UUID userA = fixture.createUser(tenantA);
        fixture.createUser(tenantB);

        tenantContext.setTenantId(tenantA);
        List<UUID> seen = transactionTemplate.execute(status ->
                jdbcTemplate.queryForList(
                        "SELECT id FROM users WHERE deleted_at IS NULL", UUID.class));

        assertThat(seen).containsExactly(userA);
    }

    @Test
    void list_whenQueryRunsOnRawConnectionWithoutTenantBinding_leaksAllTenants()
            throws SQLException {
        UUID userA = fixture.createUser(tenantA);
        UUID userB = fixture.createUser(tenantB);

        List<UUID> seen = new ArrayList<>();
        try (
                Connection raw = dataSource.getConnection();
                PreparedStatement statement = raw.prepareStatement(
                        "SELECT id FROM users WHERE deleted_at IS NULL");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                seen.add(rs.getObject("id", UUID.class));
            }
        }

        assertThat(seen)
                .as("соединение без привязки к тенант-контексту - защита не держит")
                .contains(userA, userB);
    }

    @Test
    void find_whenIdBelongsToAnotherTenant_returnsNull() {
        UUID foreignId = fixture.createUser(tenantA);

        tenantContext.setTenantId(tenantB);
        UserEntity found = transactionTemplate.execute(status -> {
            entityManager.clear();
            return entityManager.find(UserEntity.class, foreignId);
        });

        assertThat(found).isNull();
    }

    @Test
    void nativeDelete_whenBelongsToAnotherTenant_doesNotRemoveRow() {
        UUID foreignId = fixture.createUser(tenantA);

        tenantContext.setTenantId(tenantB);
        Integer deleted = transactionTemplate.execute(status ->
                entityManager.createNativeQuery("DELETE FROM users WHERE id = :id")
                        .setParameter("id", foreignId)
                        .executeUpdate());

        assertThat(deleted).isNotNull().isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?", Integer.class, foreignId))
                .isEqualTo(1);
    }

    @Test
    void update_whenIdBelongsToAnotherTenant_doesNotChangeRow() {
        UUID foreignId = fixture.createUser(tenantA);

        tenantContext.setTenantId(tenantB);
        Integer updated = transactionTemplate.execute(status ->
                entityManager.createQuery(
                                "update UserEntity u set u.status = :status where u.id = :id")
                        .setParameter("status", UserStatus.BLOCKED)
                        .setParameter("id", foreignId)
                        .executeUpdate());

        assertThat(updated).isNotNull().isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE id = ?", String.class, foreignId))
                .isEqualTo(UserStatus.ACTIVE.name());
    }

    @Test
    void save_whenEntityHasForeignTenant_storesTenantFromContext() {
        tenantContext.setTenantId(tenantA);

        UserEntity user = new UserEntity(tenantB, "new.user@example.com", "hash",
                UserStatus.ACTIVE);

        UserEntity saved = transactionTemplate.execute(status -> userRepository.save(user));

        Assertions.assertNotNull(saved);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM users WHERE id = ?", UUID.class, saved.getId()))
                .isEqualTo(tenantA);
    }

    @Test
    void rls_whenUsersTableExists_isForced() {
        Boolean forced = jdbcTemplate.queryForObject(
                """
                    SELECT c.relforcerowsecurity
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                        AND c.relname = 'users'
                """, Boolean.class);

        assertThat(forced).isTrue();
    }
}

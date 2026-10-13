package ru.practicum.crm.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.comment.domain.RequestComment;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.testsupport.TenantTestFixture;

@Import(TenantTestFixture.class)
class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantTestFixture fixture;

    @Autowired
    private RequestCommentRepository commentRepository;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID requestA;
    private UUID authorA;

    @BeforeEach
    void createTenants() {
        tenantA = fixture.createTenant();
        tenantB = fixture.createTenant();
        authorA = fixture.createUser(tenantA);
        requestA = fixture.createRequest(tenantA, authorA);
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void list_whenFilterEnabled_containsOnlyCurrentTenant() {
        UUID authorB = fixture.createUser(tenantB);
        UUID requestB = fixture.createRequest(tenantB, authorB);
        UUID commentA = insertComment(tenantA, requestA, authorA);
        insertComment(tenantB, requestB, authorB);

        tenantContext.setTenantId(tenantA);
        List<UUID> seen = transactionTemplate.execute(status ->
                jdbcTemplate.queryForList("SELECT id FROM request_comments", UUID.class));

        assertThat(seen).containsExactly(commentA);
    }

    @Test
    void find_whenIdBelongsToAnotherTenant_returnsNull() {
        UUID foreignId = insertComment(tenantA, requestA, authorA);

        tenantContext.setTenantId(tenantB);
        RequestComment found = transactionTemplate.execute(status -> {
            entityManager.clear();
            return entityManager.find(RequestComment.class, foreignId);
        });

        assertThat(found).isNull();
    }

    @Test
    void nativeDelete_whenBelongsToAnotherTenant_doesNotRemoveRow() {
        UUID foreignId = insertComment(tenantA, requestA, authorA);

        tenantContext.setTenantId(tenantB);
        Integer deleted = transactionTemplate.execute(status ->
                entityManager.createNativeQuery("DELETE FROM request_comments WHERE id = :id")
                        .setParameter("id", foreignId)
                        .executeUpdate());

        assertThat(deleted).isNotNull().isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_comments WHERE id = ?", Integer.class, foreignId))
                .isEqualTo(1);
    }

    @Test
    void save_whenEntityHasForeignTenant_storesTenantFromContext() {
        tenantContext.setTenantId(tenantA);

        RequestComment comment = new RequestComment(tenantB, requestA, authorA,
                "Комментарий с подменённым арендатором", false);

        RequestComment saved = transactionTemplate.execute(status ->
                commentRepository.save(comment));

        Assertions.assertNotNull(saved);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM request_comments WHERE id = ?", UUID.class,
                saved.getId())).isEqualTo(tenantA);
    }

    @Test
    void rls_whenRequestCommentsTableExists_isForced() {
        Boolean forced = jdbcTemplate.queryForObject(
                """
                    SELECT c.relforcerowsecurity
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                        AND c.relname = 'request_comments'
                """, Boolean.class);

        assertThat(forced).isTrue();
    }

    private UUID insertComment(UUID tenantId, UUID requestId, UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_comments (id, tenant_id, request_id, author_id,"
                        + " text, visible_to_client, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'Комментарий', false, now(), now())",
                id, tenantId, requestId, authorId);
        return id;
    }
}

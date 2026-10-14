package ru.practicum.crm.attachment.repository;

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
import ru.practicum.crm.attachment.domain.FileReference;
import ru.practicum.crm.attachment.domain.RequestAttachment;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.testsupport.TenantTestFixture;

@Import(TenantTestFixture.class)
class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    private static final FileReference REPORT =
            new FileReference("media-object-1", "отчёт.pdf", 2048, "application/pdf");

    @Autowired
    private TenantTestFixture fixture;

    @Autowired
    private RequestAttachmentRepository attachmentRepository;

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
        UUID attachmentA = insertAttachment(tenantA, requestA, authorA, "object-a");
        insertAttachment(tenantB, requestB, authorB, "object-b");

        tenantContext.setTenantId(tenantA);
        List<UUID> seen = transactionTemplate.execute(status ->
                jdbcTemplate.queryForList("SELECT id FROM request_attachments", UUID.class));

        assertThat(seen).containsExactly(attachmentA);
    }

    @Test
    void find_whenIdBelongsToAnotherTenant_returnsNull() {
        UUID foreignId = insertAttachment(tenantA, requestA, authorA, "object-a");

        tenantContext.setTenantId(tenantB);
        RequestAttachment found = transactionTemplate.execute(status -> {
            entityManager.clear();
            return entityManager.find(RequestAttachment.class, foreignId);
        });

        assertThat(found).isNull();
    }

    @Test
    void nativeDelete_whenBelongsToAnotherTenant_doesNotRemoveRow() {
        UUID foreignId = insertAttachment(tenantA, requestA, authorA, "object-a");

        tenantContext.setTenantId(tenantB);
        Integer deleted = transactionTemplate.execute(status ->
                entityManager.createNativeQuery("DELETE FROM request_attachments WHERE id = :id")
                        .setParameter("id", foreignId)
                        .executeUpdate());

        assertThat(deleted).isNotNull().isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_attachments WHERE id = ?", Integer.class,
                foreignId)).isEqualTo(1);
    }

    @Test
    void save_whenEntityHasForeignTenant_storesTenantFromContext() {
        tenantContext.setTenantId(tenantA);

        RequestAttachment attachment = new RequestAttachment(tenantB, requestA, authorA, REPORT);

        RequestAttachment saved = transactionTemplate.execute(status ->
                attachmentRepository.save(attachment));

        Assertions.assertNotNull(saved);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM request_attachments WHERE id = ?", UUID.class,
                saved.getId())).isEqualTo(tenantA);
    }

    @Test
    void rls_whenRequestAttachmentsTableExists_isForced() {
        Boolean forced = jdbcTemplate.queryForObject(
                """
                    SELECT c.relforcerowsecurity
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                        AND c.relname = 'request_attachments'
                """, Boolean.class);

        assertThat(forced).isTrue();
    }

    private UUID insertAttachment(UUID tenantId, UUID requestId, UUID authorId, String objectId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_attachments (id, tenant_id, request_id,"
                        + " author_id, object_id, file_name, size_bytes, content_type, created_at,"
                        + " updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'файл.pdf', 1024, 'application/pdf', now(),"
                        + " now())",
                id, tenantId, requestId, authorId, objectId);
        return id;
    }
}

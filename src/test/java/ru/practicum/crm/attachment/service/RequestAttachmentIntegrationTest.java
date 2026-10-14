package ru.practicum.crm.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.attachment.domain.FileReference;
import ru.practicum.crm.attachment.domain.RequestAttachment;
import ru.practicum.crm.attachment.repository.RequestAttachmentRepository;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Вложения на реальной базе (T-078): хранение ссылки, запрет повторной привязки, изоляция
 * арендаторов, ограничения самой базы и выборка вложений заявки.
 *
 * <p>Заявки и пользователи создаются запросами SQL: пакет вложений не зависит от пакетов заявок
 * и пользователей, и тесты эту границу тоже не переходят.
 */
class RequestAttachmentIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610020830";
    private static final FileReference REPORT =
            new FileReference("media-object-42", "отчёт.pdf", 2048, "application/pdf");

    @Autowired
    private RequestAttachmentService attachments;

    @Autowired
    private RequestAttachmentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private UUID authorA;
    private UUID authorB;
    private UUID requestA;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant();
        tenantB = insertTenant();
        authorA = insertUser(tenantA);
        authorB = insertUser(tenantB);
        requestA = insertRequest(tenantA, authorA);
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    @Test
    void attach_storesLinkToExternalObjectWithAuthorAndFileDetails() {
        UUID id = attachments.attach(tenantA, requestA, authorA, REPORT).getId();

        RequestAttachment stored = repository.findByIdAndTenantId(id, tenantA).orElseThrow();
        assertThat(stored.getRequestId()).isEqualTo(requestA);
        assertThat(stored.getAuthorId()).isEqualTo(authorA);
        assertThat(stored.getObjectId()).isEqualTo("media-object-42");
        assertThat(stored.getFileName()).isEqualTo("отчёт.pdf");
        assertThat(stored.getSizeBytes()).isEqualTo(2048);
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    /** Содержимого файла в базе нет: в таблице вложений нет ни одной двоичной колонки. */
    @Test
    void table_hasNoColumnForFileContent() {
        List<String> binaryColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = 'request_attachments'"
                        + " AND data_type IN ('bytea', 'oid')", String.class);

        assertThat(binaryColumns).isEmpty();
    }

    @Test
    void attach_whenSameObjectAttachedToSameRequestAgain_isRejectedAndStoredOnce() {
        attachments.attach(tenantA, requestA, authorA, REPORT);

        ApiException thrown = catchThrowableOfType(
                () -> attachments.attach(tenantA, requestA, authorA, REPORT),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(firstPage(tenantA, requestA)).hasSize(1);
    }

    @Test
    void duplicateLink_insertedPastService_isRejectedByDatabase() {
        attachments.attach(tenantA, requestA, authorA, REPORT);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO request_attachments (id,"
                + " tenant_id, request_id, author_id, object_id, file_name, size_bytes,"
                + " content_type, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'media-object-42', 'копия.pdf', 1, 'application/pdf',"
                + " now(), now())", UUID.randomUUID(), tenantA, requestA, authorA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_attachments_object_unique");
    }

    @Test
    void attach_whenSameObjectAttachedToAnotherRequest_isAllowed() {
        UUID anotherRequest = insertRequest(tenantA, authorA);
        attachments.attach(tenantA, requestA, authorA, REPORT);

        attachments.attach(tenantA, anotherRequest, authorA, REPORT);

        assertThat(firstPage(tenantA, anotherRequest)).hasSize(1);
    }

    @Test
    void attachment_ofAnotherTenant_isNeitherFoundNorListed() {
        UUID id = attachments.attach(tenantA, requestA, authorA, REPORT).getId();

        assertThat(repository.findByIdAndTenantId(id, tenantB)).isEmpty();
        assertThat(firstPage(tenantB, requestA)).isEmpty();
    }

    @Test
    void attach_toRequestOfAnotherTenant_isRejectedByDatabase() {
        assertThatThrownBy(() -> attachments.attach(tenantB, requestA, authorB, REPORT))
                .rootCause().hasMessageContaining("request_attachments_request_fkey");

        assertThat(firstPage(tenantA, requestA)).isEmpty();
    }

    @Test
    void attach_byUnknownAuthor_isRejectedByDatabase() {
        assertThatThrownBy(() -> attachments.attach(tenantA, requestA, UUID.randomUUID(), REPORT))
                .rootCause().hasMessageContaining("request_attachments_author_id_fkey");
    }

    /**
     * Сейчас база не проверяет, что автор из того же арендатора, что и заявка: для составного
     * внешнего ключа в {@code users} нет уникальности {@code (tenant_id, id)}. Тест фиксирует это
     * поведение, как и такой же тест комментариев (T-074): когда проверка появится, он упадёт и
     * напомнит обновить описание модели.
     */
    @Test
    void attach_byAuthorOfAnotherTenant_isNotRejectedByDatabaseYet() {
        UUID id = attachments.attach(tenantA, requestA, authorB, REPORT).getId();

        assertThat(repository.findByIdAndTenantId(id, tenantA))
                .map(RequestAttachment::getAuthorId).contains(authorB);
    }

    @Test
    void attachments_ofRequest_containOnlyItsOwnInOrderOfAdding() {
        UUID anotherRequest = insertRequest(tenantA, authorA);
        attachments.attach(tenantA, requestA, authorA, file("object-1", "первый.pdf"));
        attachments.attach(tenantA, anotherRequest, authorA, file("object-2", "чужой.pdf"));
        attachments.attach(tenantA, requestA, authorA, file("object-3", "второй.pdf"));

        assertThat(firstPage(tenantA, requestA)).extracting(RequestAttachment::getFileName)
                .containsExactly("первый.pdf", "второй.pdf");
        assertThat(firstPage(tenantA, anotherRequest)).extracting(RequestAttachment::getFileName)
                .containsExactly("чужой.pdf");
    }

    @Test
    void negativeSize_insertedPastService_isRejectedByDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO request_attachments (id,"
                + " tenant_id, request_id, author_id, object_id, file_name, size_bytes,"
                + " content_type, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'media-object-7', 'файл.pdf', -1, 'application/pdf',"
                + " now(), now())", UUID.randomUUID(), tenantA, requestA, authorA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_attachments_size_check");
    }

    /**
     * Пятьдесят вложений у одной заявки и двадцать тысяч у соседней: вложения заявки выбираются
     * по индексу уникальности, который начинается с арендатора и заявки, а не перебором таблицы.
     */
    @Test
    void attachmentsOfRequest_whenTableIsLarge_areSelectedByIndex() {
        UUID busyRequest = insertRequest(tenantA, authorA);
        jdbcTemplate.update("INSERT INTO request_attachments (id, tenant_id, request_id,"
                + " author_id, object_id, file_name, size_bytes, content_type, created_at,"
                + " updated_at)"
                + " SELECT gen_random_uuid(), ?, CASE WHEN i <= 50 THEN ?::uuid ELSE ?::uuid END,"
                + " ?, 'object-' || i, 'файл-' || i || '.pdf', i, 'application/pdf',"
                + " now() - (i || ' seconds')::interval, now()"
                + " FROM generate_series(1, 20050) AS s(i)",
                tenantA, requestA, busyRequest, authorA);
        jdbcTemplate.execute("ANALYZE request_attachments");

        List<String> plan = jdbcTemplate.queryForList("EXPLAIN SELECT * FROM request_attachments"
                + " WHERE tenant_id = '" + tenantA + "' AND request_id = '" + requestA + "'"
                + " ORDER BY created_at, id LIMIT 20", String.class);

        assertThat(String.join("\n", plan))
                .as("план страницы вложений заявки на таблице из 20 050 вложений")
                .contains("request_attachments_object_unique")
                .doesNotContain("Seq Scan");
    }

    private static FileReference file(String objectId, String fileName) {
        return new FileReference(objectId, fileName, 1024, "application/pdf");
    }

    /** Все вложения заявки — первая страница наибольшего размера; в тестах их меньше. */
    private List<RequestAttachment> firstPage(UUID tenantId, UUID requestId) {
        return attachments.attachments(tenantId, requestId,
                PageRequest.of(0, PageRequests.MAX_SIZE)).getContent();
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                            + " VALUES (?, 'Арендатор', ?, true, now(), now())",
                id, "tenant-" + id);
        return id;
    }

    private UUID insertUser(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, email, password_hash)"
                + " VALUES (?, ?, ?, 'hash')", id, tenantId, id + "@example.com");
        return id;
    }

    private UUID insertRequest(UUID tenantId, UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, created_at, updated_at)"
                + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', 'NEW', ?, now(), now())",
                id, tenantId, authorId);
        return id;
    }
}

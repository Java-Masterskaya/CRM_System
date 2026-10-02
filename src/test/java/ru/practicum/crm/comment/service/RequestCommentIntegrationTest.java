package ru.practicum.crm.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.comment.domain.RequestComment;
import ru.practicum.crm.comment.repository.RequestCommentRepository;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Комментарии на реальной базе (T-074): хранение, изоляция арендаторов, ограничения самой базы,
 * постраничное чтение и план выборки комментариев заявки.
 *
 * <p>Заявки и пользователи создаются запросами SQL: пакет комментариев не зависит от пакетов
 * заявок и пользователей, и тесты эту границу тоже не переходят.
 */
class RequestCommentIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610020810";
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, PageRequests.MAX_SIZE);

    @Autowired
    private RequestCommentService comments;

    @Autowired
    private RequestCommentRepository repository;

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
    void add_storesCommentWithAuthorTimeAndVisibility() {
        UUID id = comments.add(tenantA, requestA, authorA, "Уточните период выгрузки", true)
                .getId();

        RequestComment stored = repository.findByIdAndTenantId(id, tenantA).orElseThrow();
        assertThat(stored.getRequestId()).isEqualTo(requestA);
        assertThat(stored.getAuthorId()).isEqualTo(authorA);
        assertThat(stored.getText()).isEqualTo("Уточните период выгрузки");
        assertThat(stored.isVisibleToClient()).isTrue();
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    void visibility_whenNotSpecifiedOnInsert_isInternalByDefault() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_comments (id, tenant_id, request_id, author_id,"
                + " text, created_at, updated_at) VALUES (?, ?, ?, ?, 'Заметка', now(), now())",
                id, tenantA, requestA, authorA);

        assertThat(repository.findByIdAndTenantId(id, tenantA).orElseThrow().isVisibleToClient())
                .as("без явного признака комментарий внутренний").isFalse();
    }

    @Test
    void comment_ofAnotherTenant_isNeitherFoundNorListed() {
        UUID id = comments.add(tenantA, requestA, authorA, "Внутренняя заметка", false).getId();

        assertThat(repository.findByIdAndTenantId(id, tenantB)).isEmpty();
        assertThat(firstPage(tenantB, requestA)).isEmpty();
        assertThat(firstPage(tenantA, requestA)).extracting(RequestComment::getId)
                .containsExactly(id);
    }

    @Test
    void add_toRequestOfAnotherTenant_isRejectedByDatabase() {
        assertThatThrownBy(() -> comments.add(tenantB, requestA, authorB, "Чужая заявка", false))
                .rootCause().hasMessageContaining("request_comments_request_fkey");

        assertThat(firstPage(tenantA, requestA)).isEmpty();
        assertThat(firstPage(tenantB, requestA)).isEmpty();
    }

    @Test
    void add_byUnknownAuthor_isRejectedByDatabase() {
        assertThatThrownBy(() -> comments.add(tenantA, requestA, UUID.randomUUID(), "Текст", false))
                .rootCause().hasMessageContaining("request_comments_author_id_fkey");
    }

    /**
     * Сейчас база не проверяет, что автор из того же арендатора, что и заявка: для составного
     * внешнего ключа в {@code users} нет уникальности {@code (tenant_id, id)}. Тест фиксирует это
     * поведение: когда такая проверка появится, он упадёт и напомнит обновить описание модели.
     */
    @Test
    void add_byAuthorOfAnotherTenant_isNotRejectedByDatabaseYet() {
        UUID id = comments.add(tenantA, requestA, authorB, "Автор из другого арендатора", false)
                .getId();

        assertThat(repository.findByIdAndTenantId(id, tenantA)).map(RequestComment::getAuthorId)
                .contains(authorB);
    }

    @Test
    void commentsForClient_containOnlyCommentsVisibleToClient() {
        comments.add(tenantA, requestA, authorA, "Уточните период", true);
        comments.add(tenantA, requestA, authorA, "Клиент путает отчёты, проверить вручную", false);
        comments.add(tenantA, requestA, authorA, "Отчёт готов", true);

        assertThat(comments.commentsForClient(tenantA, requestA, FIRST_PAGE).getContent())
                .extracting(RequestComment::getText)
                .containsExactly("Уточните период", "Отчёт готов");
        assertThat(comments.commentsForClient(tenantB, requestA, FIRST_PAGE).getContent())
                .as("клиентский список тоже читается только в своём арендаторе").isEmpty();
        assertThat(firstPage(tenantA, requestA)).as("команда видит и внутренние").hasSize(3);
    }

    @Test
    void text_longerThanLimit_isRejectedByDatabaseEvenWithoutService() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO request_comments (id, tenant_id,"
                + " request_id, author_id, text, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, repeat('я', ?), now(), now())",
                UUID.randomUUID(), tenantA, requestA, authorA, RequestComment.TEXT_MAX_LENGTH + 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_comments_text_length_check");
    }

    @Test
    void comments_whenReadPageByPage_returnEveryCommentOnceInSameOrder() {
        for (int number = 1; number <= 5; number++) {
            comments.add(tenantA, requestA, authorA, "Комментарий " + number, number % 2 == 0);
        }

        Page<RequestComment> page = comments.commentsForTeam(tenantA, requestA,
                PageRequest.of(0, 2));
        List<UUID> pageByPage = new ArrayList<>(ids(page.getContent()));
        while (page.hasNext()) {
            page = comments.commentsForTeam(tenantA, requestA, page.nextPageable());
            pageByPage.addAll(ids(page.getContent()));
        }

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(pageByPage).doesNotHaveDuplicates()
                .containsExactlyElementsOf(ids(firstPage(tenantA, requestA)));
        assertThat(firstPage(tenantA, requestA)).extracting(RequestComment::getText)
                .containsExactly("Комментарий 1", "Комментарий 2", "Комментарий 3",
                        "Комментарий 4", "Комментарий 5");
    }

    /**
     * Тысяча комментариев у одной заявки и двадцать тысяч у соседней: страница комментариев
     * берётся по индексу, а не перебором таблицы.
     */
    @Test
    void commentsOfRequest_whenRequestHasThousandComments_areSelectedByIndex() {
        UUID busyRequest = insertRequest(tenantA, authorA);
        jdbcTemplate.update("INSERT INTO request_comments (id, tenant_id, request_id, author_id,"
                + " text, visible_to_client, created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, CASE WHEN i <= 1000 THEN ?::uuid ELSE ?::uuid END,"
                + " ?, 'Комментарий ' || i, i % 2 = 0, now() - (i || ' seconds')::interval, now()"
                + " FROM generate_series(1, 21000) AS s(i)",
                tenantA, requestA, busyRequest, authorA);
        jdbcTemplate.execute("ANALYZE request_comments");

        List<String> plan = jdbcTemplate.queryForList("EXPLAIN SELECT * FROM request_comments"
                + " WHERE tenant_id = '" + tenantA + "' AND request_id = '" + requestA + "'"
                + " ORDER BY created_at, id LIMIT 20", String.class);

        assertThat(String.join("\n", plan))
                .as("план страницы комментариев заявки на таблице из 21 000 комментариев")
                .contains("idx_request_comments_tenant_request_created_at")
                .doesNotContain("Seq Scan");
    }

    /** Все комментарии заявки — первая страница наибольшего размера; в тестах их меньше. */
    private List<RequestComment> firstPage(UUID tenantId, UUID requestId) {
        return comments.commentsForTeam(tenantId, requestId, FIRST_PAGE).getContent();
    }

    private static List<UUID> ids(List<RequestComment> list) {
        return list.stream().map(RequestComment::getId).toList();
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", id);
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

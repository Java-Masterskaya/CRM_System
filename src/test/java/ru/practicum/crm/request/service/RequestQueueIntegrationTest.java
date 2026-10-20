package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Очередь работ оператора (T-052) по HTTP, с настоящими токенами: какие заявки в неё попадают,
 * порядок, фильтр по статусу, страницы, отказ клиенту и план запроса.
 *
 * <p>Заявки создаются запросами SQL. Исполнитель — пользователь, вошедший как оператор; заявки
 * других операторов назначены на идентификаторы, которых нет среди пользователей: на очередь это
 * не влияет, внешнего ключа на исполнителя нет.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestQueueIntegrationTest extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RequestQueueIntegrationTest.class);
    private static final String MIGRATION_VERSION = "202610201000";
    private static final String QUEUE = "/admin/queue";
    private static final String PASSWORD = "StrongPassword1!";
    private static final String OPERATOR = "operator@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private UUID operatorId;
    private String operatorToken;
    private UUID otherOperator;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        operatorId = insertUser(tenant, OPERATOR, "OPERATOR");
        operatorToken = login(tenant, OPERATOR);
        otherOperator = UUID.randomUUID();
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Миграция записана, а прежний индекс (tenant_id, assignee_id) заменён новым. */
    @Test
    void migration_whenApplied_replacesAssigneeIndex() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes"
                + " WHERE schemaname = current_schema() AND tablename = 'requests'"
                + " AND indexname LIKE 'idx_requests_tenant_assignee%'", String.class))
                .containsExactly("idx_requests_tenant_assignee_queue");
    }

    /**
     * DoD: в очереди только незавершённые заявки текущего оператора. Его завершённых, чужих,
     * свободных, удалённых и заявок другого арендатора — даже назначенных на тот же
     * идентификатор — в ней нет.
     */
    @Test
    void queue_containsOnlyOwnUnfinishedRequests() throws Exception {
        List<String> own = new ArrayList<>();
        for (String status : List.of("NEW", "CONTACTED", "IN_PROGRESS", "ON_HOLD")) {
            own.add(id(insertRequest(tenant, status, "NORMAL", operatorId, daysAgo(1))));
        }
        for (String status : List.of("DONE", "REJECTED", "CANCELLED")) {
            insertRequest(tenant, status, "NORMAL", operatorId, daysAgo(1));
        }
        insertRequest(tenant, "NEW", "NORMAL", otherOperator, daysAgo(1));
        insertRequest(tenant, "NEW", "NORMAL", null, daysAgo(1));
        UUID deleted = insertRequest(tenant, "NEW", "NORMAL", operatorId, daysAgo(1));
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);
        insertRequest(insertTenant(), "NEW", "NORMAL", operatorId, daysAgo(1));

        assertThat(ids()).containsExactlyInAnyOrderElementsOf(own);
    }

    /** DoD: сначала более приоритетные, при равном приоритете — созданные раньше. */
    @Test
    void queue_ordersByPriorityThenOldestFirst() throws Exception {
        final UUID low = insertRequest(tenant, "NEW", "LOW", operatorId, daysAgo(9));
        final UUID urgentNewer = insertRequest(tenant, "NEW", "URGENT", operatorId, daysAgo(1));
        final UUID urgentOlder = insertRequest(tenant, "IN_PROGRESS", "URGENT", operatorId,
                daysAgo(3));
        final UUID high = insertRequest(tenant, "ON_HOLD", "HIGH", operatorId, daysAgo(2));
        final UUID normal = insertRequest(tenant, "CONTACTED", "NORMAL", operatorId, daysAgo(5));

        assertThat(ids()).containsExactly(id(urgentOlder), id(urgentNewer), id(high),
                id(normal), id(low));
    }

    /** Фильтр по статусу — только среди незавершённых; можно несколько. */
    @Test
    void queue_statusFilter_narrowsToGivenUnfinishedStatuses() throws Exception {
        final UUID fresh = insertRequest(tenant, "NEW", "NORMAL", operatorId, daysAgo(3));
        final UUID contacted = insertRequest(tenant, "CONTACTED", "NORMAL", operatorId,
                daysAgo(2));
        final UUID onHold = insertRequest(tenant, "ON_HOLD", "NORMAL", operatorId, daysAgo(1));

        assertThat(ids("status", "ON_HOLD")).containsExactly(id(onHold));
        assertThat(ids("status", "NEW,CONTACTED")).containsExactly(id(fresh), id(contacted));
        assertThat(ids("status", "NEW", "status", "ON_HOLD"))
                .containsExactly(id(fresh), id(onHold));
    }

    /**
     * DoD: заявки других операторов и других арендаторов не появляются ни при каких параметрах.
     * У чужих заявок — тот же статус, приоритет и время, что у своей.
     */
    @Test
    void queue_anyParameters_neverShowOtherOperatorsOrTenants() throws Exception {
        UUID own = insertRequest(tenant, "IN_PROGRESS", "URGENT", operatorId, daysAgo(1));
        insertRequest(tenant, "IN_PROGRESS", "URGENT", otherOperator, daysAgo(1));
        insertRequest(insertTenant(), "IN_PROGRESS", "URGENT", operatorId, daysAgo(1));

        List<String[]> combinations = List.of(
                new String[] {},
                new String[] {"status", "IN_PROGRESS"},
                new String[] {"status", "NEW,CONTACTED,IN_PROGRESS,ON_HOLD"},
                new String[] {"page", "0", "size", "100"});
        for (String[] params : combinations) {
            assertThat(ids(params)).as(String.join(" ", params)).containsExactly(id(own));
        }
    }

    /** DoD: список отдаётся страницами с общим количеством. */
    @Test
    void queue_isPagedWithTotals() throws Exception {
        List<String> ordered = new ArrayList<>();
        for (int day = 5; day >= 1; day--) {
            ordered.add(id(insertRequest(tenant, "NEW", "NORMAL", operatorId, daysAgo(day))));
        }

        queue(operatorToken, "page", "1", "size", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content[0].id").value(ordered.get(2)))
                .andExpect(jsonPath("$.content[1].id").value(ordered.get(3)));
    }

    /**
     * Неверный запрос — ошибка валидации с именем параметра, а не пустой список: завершённый или
     * неизвестный статус, сортировка (у очереди порядок один), страница вне границ.
     */
    @ParameterizedTest
    @CsvSource({
        "status, DONE",
        "status, CLOSED",
        "sort, 'createdAt,desc'",
        "size, 101",
        "page, -1"
    })
    void queue_withInvalidParameter_isValidationErrorNamingIt(String name, String value)
            throws Exception {
        queue(operatorToken, name, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value(name));
    }

    /** Клиент получает 403: у него нет права читать заявки арендатора. */
    @Test
    void queue_byClient_isForbidden() throws Exception {
        insertUser(tenant, "client@example.com", "CLIENT");
        String clientToken = login(tenant, "client@example.com");

        queue(clientToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /**
     * DoD: план запроса очереди использует составной индекс, полного сканирования нет. Двадцать
     * тысяч заявок сотни других операторов и десяток своих — и без фильтра по статусу, и с ним.
     * Планы пишутся в журнал теста — их прикладывают к описанию PR.
     */
    @Test
    void queueQuery_whenTableIsLarge_usesCompositeIndex() {
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " priority, priority_rank, author_id, assignee_id, assigned_at, created_at,"
                + " updated_at)"
                + " SELECT gen_random_uuid(), ?, 'Тема', 'Описание',"
                + " (ARRAY['NEW', 'IN_PROGRESS', 'DONE', 'DONE'])[1 + i % 4], 'NORMAL', 2,"
                + " gen_random_uuid(), ('00000000-0000-0000-0000-' || lpad((i % 100)::text, 12,"
                + " '0'))::uuid, now(), now() - i * interval '1 minute', now()"
                + " FROM generate_series(1, 20000) AS s(i)", tenant.id());
        for (int i = 0; i < 10; i++) {
            insertRequest(tenant, i % 2 == 0 ? "NEW" : "DONE", "HIGH", operatorId, daysAgo(i));
        }
        jdbcTemplate.execute("ANALYZE requests");
        String own = "tenant_id = '" + tenant.id() + "' AND NOT deleted AND assignee_id = '"
                + operatorId + "'";
        String order = " ORDER BY priority_rank DESC, created_at, id LIMIT 20";

        Map<String, String> queries = Map.of(
                "без фильтра", own + " AND status IN ('NEW', 'CONTACTED', 'IN_PROGRESS',"
                        + " 'ON_HOLD')",
                "status=NEW", own + " AND status IN ('NEW')");
        queries.forEach((name, condition) -> {
            String plan = plan("SELECT * FROM requests WHERE " + condition + order);
            LOG.info("План очереди, {}:\n{}", name, plan);
            assertThat(plan).as(name).contains("idx_requests_tenant_assignee_queue")
                    .doesNotContain("Seq Scan");
        });
    }

    private ResultActions queue(String accessToken, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(QUEUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** Идентификаторы заявок первой страницы очереди оператора в порядке выдачи. */
    private List<String> ids(String... params) throws Exception {
        String body = queue(operatorToken, params)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(body).get("content")) {
            ids.add(item.get("id").asText());
        }
        return ids;
    }

    private String plan(String sql) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
    }

    private static String id(UUID id) {
        return id.toString();
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    /** Исполнитель и время назначения — вместе, как требует {@code requests_assignment_check}. */
    private UUID insertRequest(AuthTestFixture.TestTenant tenantOfRequest, String status,
            String priority, UUID assigneeId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " priority, priority_rank, author_id, assignee_id, assigned_at, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?, ?, ?,"
                + " ?, ?::timestamptz, ?::timestamptz, now())", id, tenantOfRequest.id(), status,
                priority, RequestPriority.valueOf(priority).getRank(), UUID.randomUUID(),
                assigneeId, assigneeId == null ? null : createdAt.toString(),
                createdAt.toString());
        return id;
    }

    /** Арендатор с ролями и правами по умолчанию, как у нового арендатора. */
    private AuthTestFixture.TestTenant insertTenant() {
        AuthTestFixture.TestTenant created = authTestFixture.createTenant(
                "queue-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, created.id());
        return created;
    }

    private UUID insertUser(AuthTestFixture.TestTenant tenantOfUser, String email,
            String roleCode) {
        UUID id = authTestFixture.createUser(tenantOfUser, email, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenantOfUser.id(), roleCode);
        return id;
    }

    private String login(AuthTestFixture.TestTenant tenantOfUser, String email)
            throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenantOfUser.slug(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}

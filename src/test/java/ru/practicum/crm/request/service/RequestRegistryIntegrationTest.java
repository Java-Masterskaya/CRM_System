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
import java.util.HashMap;
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
 * Реестр заявок административного контура (T-040) по HTTP, с настоящими токенами: каждый фильтр
 * и их сочетания, только свой арендатор, «просроченные» только среди незавершённых, отказ клиенту,
 * ошибки валидации и планы запросов по каждому фильтру.
 *
 * <p>Заявки создаются запросами SQL: так в одном тесте видны статус, приоритет, тип, время
 * создания и признаки просрочки.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestRegistryIntegrationTest extends BaseIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RequestRegistryIntegrationTest.class);
    private static final String REGISTRY = "/admin/requests";
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
    private AuthTestFixture.TestTenant other;
    private String operatorToken;
    private UUID export;
    private UUID removal;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        other = insertTenant();
        insertUser(tenant, OPERATOR, "OPERATOR");
        operatorToken = login(tenant, OPERATOR);
        export = insertType(tenant, "Выгрузка данных");
        removal = insertType(tenant, "Удаление данных");
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Без фильтров — все заявки арендатора, сначала новые. Удалённые и чужие в реестр не
     * попадают. Строка реестра несёт поля заявки и её версию.
     */
    @Test
    void list_withoutFilters_returnsTenantRequestsNewestFirst() throws Exception {
        final UUID oldest = insertRequest(tenant, "NEW", "LOW", export, daysAgo(3));
        final UUID newest = insertRequest(tenant, "IN_PROGRESS", "URGENT", removal, daysAgo(1));
        final UUID middle = insertRequest(tenant, "DONE", "NORMAL", null, daysAgo(2));
        UUID deleted = insertRequest(tenant, "NEW", "LOW", export, daysAgo(1));
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);
        insertRequest(other, "NEW", "LOW", null, daysAgo(1));

        registry(operatorToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.content[0].subject").value("Выгрузка за квартал"))
                .andExpect(jsonPath("$.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.content[0].priority").value("URGENT"))
                .andExpect(jsonPath("$.content[0].typeId").value(removal.toString()))
                .andExpect(jsonPath("$.content[0].overdue").value(false))
                .andExpect(jsonPath("$.content[0].version").value(0));
        assertThat(ids()).containsExactly(newest.toString(), middle.toString(),
                oldest.toString());
    }

    /** DoD: каждый заявленный фильтр сужает выборку. */
    @Test
    void list_eachFilter_narrowsSelection() throws Exception {
        final UUID newLow = insertRequest(tenant, "NEW", "LOW", export, daysAgo(10));
        final UUID inProgressHigh = insertRequest(tenant, "IN_PROGRESS", "HIGH", removal,
                daysAgo(5));
        final UUID doneUrgent = insertRequest(tenant, "DONE", "URGENT", export, daysAgo(1));
        final UUID onHoldNormal = insertRequest(tenant, "ON_HOLD", "NORMAL", null, daysAgo(2));

        assertThat(ids("status", "NEW")).containsExactly(id(newLow));
        assertThat(ids("status", "NEW,DONE")).as("через запятую")
                .containsExactlyInAnyOrder(id(newLow), id(doneUrgent));
        assertThat(ids("status", "NEW", "status", "IN_PROGRESS")).as("повтором")
                .containsExactlyInAnyOrder(id(newLow), id(inProgressHigh));
        assertThat(ids("typeId", export.toString()))
                .containsExactlyInAnyOrder(id(newLow), id(doneUrgent));
        assertThat(ids("priority", "HIGH")).containsExactly(id(inProgressHigh));
        assertThat(ids("createdFrom", daysAgo(6).toString())).as("начало периода входит")
                .containsExactlyInAnyOrder(id(inProgressHigh), id(doneUrgent), id(onHoldNormal));
        assertThat(ids("createdTo", daysAgo(5).toString())).as("конец периода не входит")
                .containsExactly(id(newLow));
        assertThat(ids("createdFrom", daysAgo(5).toString(), "createdTo", daysAgo(2).toString()))
                .containsExactly(id(inProgressHigh));
    }

    /** DoD: фильтры сочетаются — заявка должна подойти под каждый. */
    @Test
    void list_filtersCombine() throws Exception {
        final UUID newLow = insertRequest(tenant, "NEW", "LOW", export, daysAgo(3));
        final UUID doneLow = insertRequest(tenant, "DONE", "LOW", export, daysAgo(2));
        insertRequest(tenant, "DONE", "HIGH", removal, daysAgo(1));

        assertThat(ids("status", "DONE", "typeId", export.toString()))
                .containsExactly(id(doneLow));
        assertThat(ids("typeId", export.toString(), "priority", "LOW",
                "createdTo", daysAgo(2).toString())).containsExactly(id(newLow));
        assertThat(ids("status", "NEW", "priority", "HIGH")).isEmpty();
    }

    /**
     * Фильтр «просроченные» — только просроченные сейчас (ревью #199): срок первого ответа
     * считается, пока заявка в NEW, срок решения — пока она не завершена. Закрытые с нарушением
     * в фильтр не попадают, хотя признаки просрочки у них остаются.
     */
    @Test
    void list_overdue_returnsOnlyRequestsOverdueNow() throws Exception {
        final UUID newLateAnswer = overdueRequest("NEW", true, false);
        final UUID inProgressLate = overdueRequest("IN_PROGRESS", false, true);
        final UUID onHoldLate = overdueRequest("ON_HOLD", false, true);
        final UUID answeredLate = overdueRequest("CONTACTED", true, false);
        final UUID doneLate = overdueRequest("DONE", true, true);
        overdueRequest("REJECTED", false, true);
        overdueRequest("CANCELLED", true, true);
        insertRequest(tenant, "NEW", "NORMAL", null, daysAgo(1));
        UUID deleted = overdueRequest("NEW", true, true);
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);
        UUID foreign = insertRequest(other, "NEW", "NORMAL", null, daysAgo(1));
        jdbcTemplate.update("UPDATE requests SET first_response_overdue = TRUE WHERE id = ?",
                foreign);

        assertThat(ids("overdue", "true")).containsExactlyInAnyOrder(id(newLateAnswer),
                id(inProgressLate), id(onHoldLate));
        assertThat(ids("overdue", "false")).as("overdue=false — фильтр не задан").hasSize(8);
        assertThat(overdueFlags()).containsEntry(id(newLateAnswer), true)
                .containsEntry(id(answeredLate), false)
                .containsEntry(id(doneLate), false);
    }

    /** DoD: только заявки текущего арендатора — при любом сочетании фильтров. */
    @Test
    void list_anyFilterCombination_neverShowsOtherTenantRequests() throws Exception {
        UUID own = overdueRequest("NEW", true, false);
        jdbcTemplate.update("UPDATE requests SET type_id = ?, priority = 'URGENT',"
                + " priority_rank = 4 WHERE id = ?", export, own);
        // Чужая заявка подходит под все фильтры — даже тип тот же: внешний ключ типа арендатора
        // не проверяет, и отсечь её может только условие на арендатора.
        UUID foreign = overdueRequest(other, "NEW", true, false);
        jdbcTemplate.update("UPDATE requests SET type_id = ?, priority = 'URGENT',"
                + " priority_rank = 4 WHERE id = ?", export, foreign);
        String from = daysAgo(2).toString();

        List<String[]> combinations = List.of(
                new String[] {},
                new String[] {"status", "NEW"},
                new String[] {"typeId", export.toString()},
                new String[] {"priority", "URGENT"},
                new String[] {"createdFrom", from},
                new String[] {"overdue", "true"},
                new String[] {"status", "NEW", "typeId", export.toString(), "priority", "URGENT",
                    "createdFrom", from, "overdue", "true"});
        for (String[] filters : combinations) {
            assertThat(ids(filters)).as(String.join(" ", filters)).containsExactly(id(own));
        }
    }

    /** DoD: клиент получает 403 — у него нет права читать все заявки арендатора. */
    @Test
    void list_byClient_isForbidden() throws Exception {
        insertUser(tenant, "client@example.com", "CLIENT");
        String clientToken = login(tenant, "client@example.com");

        registry(clientToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** DoD: неверное значение фильтра — ошибка валидации с именем параметра, а не пустой список. */
    @ParameterizedTest
    @CsvSource({
        "status, CLOSED",
        "typeId, не-uuid",
        "priority, MEGA",
        "createdFrom, вчера",
        "overdue, maybe",
        "sort, subject",
        "sort, 'createdAt,up'",
        "size, 101",
        "page, -1"
    })
    void list_withInvalidFilterValue_isValidationErrorNamingParameter(String name, String value)
            throws Exception {
        insertRequest(tenant, "NEW", "NORMAL", null, daysAgo(1));

        registry(operatorToken, name, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value(name));
    }

    /** Пустой или перевёрнутый период — тоже ошибка валидации, а не пустой список. */
    @Test
    void list_withPeriodEndingBeforeItStarts_isValidationError() throws Exception {
        registry(operatorToken, "createdFrom", daysAgo(1).toString(),
                "createdTo", daysAgo(2).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("createdTo"));
    }

    /**
     * Сортировка по приоритету — по срочности, а не по алфавиту; страницы по общему контракту
     * списков. Единственное значение {@code sort=priority,desc} не режется по запятой.
     */
    @Test
    void list_sortedByPriority_isOrderedByUrgencyAndPaged() throws Exception {
        final UUID low = insertRequest(tenant, "NEW", "LOW", null, daysAgo(1));
        final UUID urgent = insertRequest(tenant, "NEW", "URGENT", null, daysAgo(2));
        final UUID normal = insertRequest(tenant, "NEW", "NORMAL", null, daysAgo(3));
        final UUID high = insertRequest(tenant, "NEW", "HIGH", null, daysAgo(4));

        assertThat(ids("sort", "priority,desc"))
                .containsExactly(id(urgent), id(high), id(normal), id(low));
        registry(operatorToken, "sort", "priority,asc", "page", "1", "size", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(id(high)))
                .andExpect(jsonPath("$.content[1].id").value(id(urgent)));
    }

    /**
     * DoD: план запроса по каждому фильтру использует индекс. Двадцать тысяч старых закрытых
     * заявок с низким приоритетом и пять свежих, под которые подходят фильтры. Планы пишутся в
     * журнал теста — их прикладывают к описанию PR.
     */
    @Test
    void registryFilters_whenTableIsLarge_useIndexes() {
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " priority, priority_rank, type_id, author_id, created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, 'Тема', 'Описание', 'DONE', 'LOW', 1, ?,"
                + " gen_random_uuid(), ?::timestamptz, now() FROM generate_series(1, 20000)",
                tenant.id(), removal, daysAgo(30).toString());
        for (int i = 0; i < 5; i++) {
            UUID fresh = insertRequest(tenant, "NEW", "URGENT", export, now.minusSeconds(60));
            jdbcTemplate.update("UPDATE requests SET first_response_overdue = TRUE WHERE id = ?",
                    fresh);
        }
        jdbcTemplate.execute("ANALYZE requests");
        String tenantOnly = "tenant_id = '" + tenant.id() + "' AND NOT deleted";
        String order = " ORDER BY created_at DESC, id LIMIT 20";

        Map<String, String> filters = Map.of(
                "status IN ('NEW')", "idx_requests_tenant_status",
                "type_id = '" + export + "'", "idx_requests_type_id",
                "priority_rank = 4", "idx_requests_tenant_priority_rank",
                "created_at >= '" + daysAgo(1) + "'::timestamptz", "idx_requests_tenant_created_at",
                "((status = 'NEW' AND first_response_overdue) OR (status IN ('NEW', 'CONTACTED',"
                        + " 'IN_PROGRESS', 'ON_HOLD') AND resolution_overdue))",
                "idx_requests_tenant_overdue");
        filters.forEach((condition, index) -> {
            String plan = plan("SELECT * FROM requests WHERE " + tenantOnly + " AND " + condition
                    + order);
            LOG.info("План реестра, условие {}:\n{}", condition, plan);
            assertThat(plan).as(condition).contains(index).doesNotContain("Seq Scan");
        });
    }

    private ResultActions registry(String accessToken, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(REGISTRY)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** Идентификаторы заявок первой страницы реестра оператора в порядке выдачи. */
    private List<String> ids(String... params) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : content(params)) {
            ids.add(item.get("id").asText());
        }
        return ids;
    }

    /** Признак «просрочена сейчас» в строке реестра — по идентификатору заявки. */
    private Map<String, Boolean> overdueFlags() throws Exception {
        Map<String, Boolean> flags = new HashMap<>();
        for (JsonNode item : content()) {
            flags.put(item.get("id").asText(), item.get("overdue").asBoolean());
        }
        return flags;
    }

    private JsonNode content(String... params) throws Exception {
        String body = registry(operatorToken, params)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("content");
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

    private UUID overdueRequest(String status, boolean firstResponse, boolean resolution) {
        return overdueRequest(tenant, status, firstResponse, resolution);
    }

    private UUID overdueRequest(AuthTestFixture.TestTenant tenantOfRequest, String status,
            boolean firstResponse, boolean resolution) {
        UUID id = insertRequest(tenantOfRequest, status, "NORMAL", null, daysAgo(1));
        jdbcTemplate.update("UPDATE requests SET first_response_overdue = ?,"
                + " resolution_overdue = ? WHERE id = ?", firstResponse, resolution, id);
        return id;
    }

    /** Ранг — по тому же соответствию, что проверяет {@code requests_priority_check}. */
    private UUID insertRequest(AuthTestFixture.TestTenant tenantOfRequest, String status,
            String priority, UUID typeId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " priority, priority_rank, type_id, author_id, created_at, updated_at)"
                + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?, ?, ?, ?,"
                + " ?::timestamptz, now())", id, tenantOfRequest.id(), status, priority,
                RequestPriority.valueOf(priority).getRank(), typeId,
                UUID.randomUUID(), createdAt.toString());
        return id;
    }

    private UUID insertType(AuthTestFixture.TestTenant tenantOfType, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, created_at,"
                + " updated_at) VALUES (?, ?, ?, now(), now())", id, tenantOfType.id(), name);
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

    /** Арендатор с ролями и правами по умолчанию, как у нового арендатора. */
    private AuthTestFixture.TestTenant insertTenant() {
        AuthTestFixture.TestTenant created = authTestFixture.createTenant(
                "registry-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, created.id());
        return created;
    }

    private void insertUser(AuthTestFixture.TestTenant tenantOfUser, String email,
            String roleCode) {
        UUID id = authTestFixture.createUser(tenantOfUser, email, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenantOfUser.id(), roleCode);
    }
}

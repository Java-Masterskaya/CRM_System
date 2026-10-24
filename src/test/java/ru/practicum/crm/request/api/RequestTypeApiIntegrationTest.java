package ru.practicum.crm.request.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Справочник типов заявок по HTTP (T-034, DoD #35), с настоящими токенами: вход, затем запрос с
 * access-токеном. Роли — по умолчанию для арендатора: {@code REQUEST_TYPE_MANAGE} есть только у
 * администратора, {@code REQUEST_READ_ALL} — у оператора и администратора, {@code REQUEST_CREATE}
 * — у клиента.
 *
 * <p>Правила маршрутов в этом контексте выключены (флаг авторизации), их проверяет
 * {@code SecurityAuthorizationRulesTest}. Здесь проверяются права на операции, изоляция
 * арендаторов и сами операции.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestTypeApiIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String ADMIN = "admin@example.com";
    private static final String OPERATOR = "operator@example.com";
    private static final String CLIENT = "client@example.com";
    private static final String ADMIN_TYPES = "/admin/request-types";
    private static final String CLIENT_TYPES = "/client/request-types";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private String adminToken;
    private String operatorToken;
    private String clientToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        insertUser(tenant, ADMIN, "ADMIN");
        insertUser(tenant, OPERATOR, "OPERATOR");
        insertUser(tenant, CLIENT, "CLIENT");
        adminToken = login(tenant, ADMIN);
        operatorToken = login(tenant, OPERATOR);
        clientToken = login(tenant, CLIENT);
    }

    /**
     * DoD: администратор создаёт тип, и он появляется в списке для выбора. Клиент видит тип без
     * служебных полей; пробелы по краям названия отброшены.
     */
    @Test
    void create_byAdmin_returnsCreatedTypeAndClientCanChooseIt() throws Exception {
        String created = createType(adminToken,
                body("  Выгрузка данных ", "Отчёты и витрины", "HIGH"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Выгрузка данных"))
                .andExpect(jsonPath("$.description").value("Отчёты и витрины"))
                .andExpect(jsonPath("$.defaultPriority").value("HIGH"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String typeId = objectMapper.readTree(created).get("id").asText();

        call(get(CLIENT_TYPES), clientToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(typeId))
                .andExpect(jsonPath("$.content[0].name").value("Выгрузка данных"))
                .andExpect(jsonPath("$.content[0].description").value("Отчёты и витрины"))
                .andExpect(jsonPath("$.content[0].active").doesNotExist())
                .andExpect(jsonPath("$.content[0].defaultPriority").doesNotExist());
    }

    /** Название уникально в арендаторе без учёта регистра: повтор — 409, второго типа нет. */
    @Test
    void create_withNameTakenInAnotherLetterCase_isConflictAndNothingAdded() throws Exception {
        createType(adminToken, body("Выгрузка", null, null)).andExpect(status().isCreated());

        createType(adminToken, body("ВЫГРУЗКА", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));

        assertThat(typeCount()).isEqualTo(1);
    }

    @Test
    void create_withBlankName_isValidationErrorOnName() throws Exception {
        createType(adminToken, body("   ", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"));

        assertThat(typeCount()).isZero();
    }

    /** Изменение заменяет карточку целиком: незаданные пояснение и приоритет становятся пустыми. */
    @Test
    void update_byAdmin_replacesWholeCard() throws Exception {
        String typeId = createdTypeId(body("Выгрузка", "Отчёты", "HIGH"));

        call(put(ADMIN_TYPES + "/" + typeId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Импорт", null, null)), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(typeId))
                .andExpect(jsonPath("$.name").value("Импорт"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.defaultPriority").doesNotExist());
    }

    /**
     * DoD: отключённый тип недоступен для новых заявок, но заявка, которая уже на него ссылается,
     * читается. Тип при этом остаётся в справочнике администратора.
     */
    @Test
    void deactivate_hidesTypeFromNewRequestsButKeepsExistingRequestsReadable() throws Exception {
        String typeId = createdTypeId(body("Выгрузка", null, null));
        UUID requestId = insertRequest(UUID.fromString(typeId));

        call(post(ADMIN_TYPES + "/" + typeId + "/deactivate"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        call(get(CLIENT_TYPES), clientToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        call(get(ADMIN_TYPES), adminToken)
                .andExpect(jsonPath("$.content[0].id").value(typeId))
                .andExpect(jsonPath("$.content[0].active").value(false));
        call(post("/client/requests").header("Idempotency-Key", "after-deactivation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "typeId", typeId,
                        "subject", "Выгрузка за квартал",
                        "description", "Нужен отчёт"))), clientToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/typeId"));
        call(get("/admin/requests/" + requestId), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeId").value(typeId));
    }

    @Test
    void activate_returnsTypeToClientList() throws Exception {
        String typeId = createdTypeId(body("Выгрузка", null, null));
        call(post(ADMIN_TYPES + "/" + typeId + "/deactivate"), adminToken)
                .andExpect(status().isOk());

        call(post(ADMIN_TYPES + "/" + typeId + "/activate"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        call(get(CLIENT_TYPES), clientToken)
                .andExpect(jsonPath("$.content[0].id").value(typeId));
    }

    /**
     * DoD: оператор без прав администратора получает отказ при попытке изменить справочник.
     * Читать справочник он может: названия типов нужны ему в реестре и при правке заявки.
     */
    @Test
    void directory_forOperator_isReadOnly() throws Exception {
        String typeId = createdTypeId(body("Выгрузка", null, null));

        call(get(ADMIN_TYPES), operatorToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(typeId));
        createType(operatorToken, body("Импорт", null, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        call(put(ADMIN_TYPES + "/" + typeId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Импорт", null, null)), operatorToken)
                .andExpect(status().isForbidden());
        call(post(ADMIN_TYPES + "/" + typeId + "/deactivate"), operatorToken)
                .andExpect(status().isForbidden());

        assertThat(typeCount()).isEqualTo(1);
    }

    /** Клиенту административный справочник закрыт, а список действующих типов — открыт. */
    @Test
    void directory_forClient_isClosedExceptActiveTypes() throws Exception {
        createdTypeId(body("Выгрузка", null, null));

        call(get(ADMIN_TYPES), clientToken).andExpect(status().isForbidden());
        createType(clientToken, body("Импорт", null, null)).andExpect(status().isForbidden());
        call(get(CLIENT_TYPES), clientToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** Администратор — не клиент: права выбирать тип для новой заявки у него нет. */
    @Test
    void activeTypes_forAdmin_isForbidden() throws Exception {
        call(get(CLIENT_TYPES), adminToken).andExpect(status().isForbidden());
    }

    /**
     * DoD: арендатор видит только свои типы, чужие не возвращаются ни в одном ответе, а изменить
     * чужой тип нельзя — для чужого арендатора его нет. Название чужого типа можно занять.
     */
    @Test
    void directory_ofAnotherTenant_isInvisibleAndNotFound() throws Exception {
        AuthTestFixture.TestTenant other = insertTenant();
        insertUser(other, ADMIN, "ADMIN");
        String otherAdminToken = login(other, ADMIN);
        String foreignId = createdTypeId(body("Выгрузка", null, null), otherAdminToken);

        createType(adminToken, body("Выгрузка", null, null)).andExpect(status().isCreated());
        call(get(ADMIN_TYPES), adminToken)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(not(foreignId)));
        call(get(CLIENT_TYPES), clientToken)
                .andExpect(jsonPath("$.totalElements").value(1));
        call(put(ADMIN_TYPES + "/" + foreignId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Чужое", null, null)), adminToken)
                .andExpect(status().isNotFound());
        call(post(ADMIN_TYPES + "/" + foreignId + "/deactivate"), adminToken)
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name || ':' || active FROM request_types WHERE id = ?::uuid",
                String.class, foreignId)).isEqualTo("Выгрузка:true");
    }

    /** Список — страница по общему контракту: с общим количеством и без чужой сортировки. */
    @Test
    void list_byPages_reportsTotalAndRejectsSortParameter() throws Exception {
        createdTypeId(body("Импорт", null, null));
        createdTypeId(body("Выгрузка", null, null));

        call(get(ADMIN_TYPES).param("size", "1"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Выгрузка"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        call(get(ADMIN_TYPES).param("sort", "createdAt,desc"), adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].parameter").value("sort"));
    }

    private ResultActions createType(String token, String json) throws Exception {
        return call(post(ADMIN_TYPES).contentType(MediaType.APPLICATION_JSON).content(json),
                token);
    }

    private String createdTypeId(String json) throws Exception {
        return createdTypeId(json, adminToken);
    }

    private String createdTypeId(String json, String token) throws Exception {
        String response = createType(token, json)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    /** Тело карточки типа; {@code null} передаётся явно, как его прислал бы клиент API. */
    private String body(String name, String description, String defaultPriority)
            throws Exception {
        Map<String, String> card = new HashMap<>();
        card.put("name", name);
        card.put("description", description);
        card.put("defaultPriority", defaultPriority);
        return objectMapper.writeValueAsString(card);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String accessToken)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
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
                "types-" + UUID.randomUUID().toString().replace("-", ""));
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

    private UUID insertRequest(UUID typeId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, type_id, subject, description,"
                + " status, author_id, created_at, updated_at) VALUES (?, ?, ?,"
                + " 'Выгрузка за квартал', 'Нужен отчёт', 'NEW', ?, now(), now())",
                id, tenant.id(), typeId, UUID.randomUUID());
        return id;
    }

    private int typeCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_types WHERE tenant_id = ?", Integer.class,
                tenant.id());
        return count == null ? 0 : count;
    }
}

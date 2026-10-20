package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Смена статуса заявки (T-044) по HTTP, с настоящими токенами: вход, затем запрос с
 * access-токеном. Роли — по умолчанию для арендатора: право {@code REQUEST_STATUS_CHANGE} есть у
 * оператора и администратора, у клиента его нет.
 *
 * <p>Заявки создаются запросами SQL, состояние после запроса тоже читается из базы: так видно, что
 * отказ не оставил следов ни в заявке, ни в истории.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestStatusIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610161200";
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

    @Autowired
    private MeterRegistry registry;

    private AuthTestFixture.TestTenant tenant;
    private UUID operatorId;
    private String operatorToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        operatorId = insertUser(tenant, OPERATOR, "OPERATOR");
        operatorToken = login(tenant, OPERATOR);
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    /**
     * DoD: валидный переход меняет статус и добавляет ровно одну запись в историю — откуда, куда,
     * кто и с каким комментарием. Ответ несёт новую версию заявки.
     */
    @Test
    void changeStatus_allowedTransition_changesStatusAndAddsExactlyOneHistoryEntry()
            throws Exception {
        UUID id = insertRequest(tenant, "IN_PROGRESS");

        changeStatus(id, operatorToken, body("DONE", "Отчёт отправлен клиенту", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(request(id)).containsEntry("status", "DONE").containsEntry("version", 1L);
        assertThat(history(id)).containsExactly(Map.of(
                "tenant_id", tenant.id(),
                "from_status", "IN_PROGRESS",
                "to_status", "DONE",
                "author_id", operatorId,
                "comment", "Отчёт отправлен клиенту"));
    }

    /**
     * Закрытия в срок и с нарушением увеличивает разные счетчики, оба с меткой арендатора.
     * Перевод не в конечный статус закрытие не считает.
     */
    @Test
    void changeStatus_whenClosedOnTimeAndLate_increasesDifferentCounters() throws Exception {
        UUID onTime = insertRequest(tenant, "IN_PROGRESS");
        UUID late = insertRequest(tenant, "IN_PROGRESS");
        jdbcTemplate.update("UPDATE requests SET first_response_overdue = TRUE WHERE id = ?",
                late);
        UUID stillOpen = insertRequest(tenant, "NEW");

        changeStatus(stillOpen, operatorToken, body("CONTACTED", null, 0))
                .andExpect(status().isOk());
        changeStatus(onTime, operatorToken, body("DONE", null, 0))
                .andExpect(status().isOk());
        changeStatus(late, operatorToken, body("DONE", null, 0))
                .andExpect(status().isOk());

        assertThat(closedCount(RequestSlaMetrics.ON_TIME)).isEqualTo(1);
        assertThat(closedCount(RequestSlaMetrics.BREACHED)).isEqualTo(1);
    }

    /**
     * Администратор ведёт заявку по шагам: каждый следующий перевод — с версией из предыдущего
     * ответа. История хранит все переходы по порядку.
     */
    @Test
    void changeStatus_stepByStepWithVersionFromResponse_recordsEveryTransitionInOrder()
            throws Exception {
        insertUser(tenant, "admin@example.com", "ADMIN");
        String adminToken = login(tenant, "admin@example.com");
        UUID id = insertRequest(tenant, "NEW");

        long version = 0;
        for (String target : List.of("CONTACTED", "IN_PROGRESS", "ON_HOLD", "IN_PROGRESS")) {
            String response = changeStatus(id, adminToken, body(target, null, version))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            version = objectMapper.readTree(response).get("version").asLong();
        }

        assertThat(history(id)).extracting(row -> row.get("from_status") + "→"
                + row.get("to_status")).containsExactly("NEW→CONTACTED", "CONTACTED→IN_PROGRESS",
                "IN_PROGRESS→ON_HOLD", "ON_HOLD→IN_PROGRESS");
        assertThat(request(id)).containsEntry("status", "IN_PROGRESS")
                .containsEntry("version", 4L);
    }

    /** DoD: невалидный переход — 409, статус заявки и история без изменений. */
    @Test
    void changeStatus_transitionNotInMatrix_isConflictAndLeavesNoTrace() throws Exception {
        UUID id = insertRequest(tenant, "NEW");

        changeStatus(id, operatorToken, body("DONE", null, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        assertThat(request(id)).containsEntry("status", "NEW").containsEntry("version", 0L);
        assertThat(history(id)).isEmpty();
    }

    /**
     * DoD: сбой при записи истории откатывает и смену статуса. Сбой настоящий, внутри транзакции:
     * на время теста таблица истории отвергает вставку триггером.
     */
    @Test
    void changeStatus_whenHistoryWriteFails_keepsPreviousStatus() throws Exception {
        UUID id = insertRequest(tenant, "IN_PROGRESS");
        jdbcTemplate.execute("CREATE FUNCTION fail_status_history_insert() RETURNS TRIGGER AS $$"
                + " BEGIN RAISE EXCEPTION 'сбой записи истории'; END; $$ LANGUAGE plpgsql");
        jdbcTemplate.execute("CREATE TRIGGER fail_status_history_insert BEFORE INSERT"
                + " ON request_status_history FOR EACH ROW"
                + " EXECUTE FUNCTION fail_status_history_insert()");
        try {
            changeStatus(id, operatorToken, body("DONE", null, 0))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_status_history_insert"
                    + " ON request_status_history");
            jdbcTemplate.execute("DROP FUNCTION fail_status_history_insert()");
        }

        assertThat(request(id)).containsEntry("status", "IN_PROGRESS")
                .containsEntry("version", 0L);
        assertThat(history(id)).isEmpty();
    }

    /**
     * DoD: переход в REJECTED без причины — ошибка валидации с указанием поля, заявка не меняется.
     * С причиной тот же перевод проходит, и причина остаётся в истории.
     */
    @Test
    void changeStatus_toRejected_requiresReason() throws Exception {
        UUID id = insertRequest(tenant, "CONTACTED");

        changeStatus(id, operatorToken, body("REJECTED", "   ", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/comment"));
        assertThat(request(id)).containsEntry("status", "CONTACTED");
        assertThat(history(id)).isEmpty();

        changeStatus(id, operatorToken, body("REJECTED", "Данные уже выгружались", 0))
                .andExpect(status().isOk());
        assertThat(history(id)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("comment",
                        "Данные уже выгружались"));
    }

    /** DoD: клиент получает 403 — даже на свою заявку: это операция административного контура. */
    @Test
    void changeStatus_byClient_isForbidden() throws Exception {
        UUID clientId = insertUser(tenant, "client@example.com", "CLIENT");
        String clientToken = login(tenant, "client@example.com");
        UUID own = insertRequest(tenant, "NEW", clientId);

        changeStatus(own, clientToken, body("CANCELLED", null, 0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(request(own)).containsEntry("status", "NEW");
        assertThat(history(own)).isEmpty();
    }

    /**
     * Заявку изменили после того, как оператор её открыл (T-045): перевод со старой версией
     * отклоняется, а не затирает чужое изменение.
     */
    @Test
    void changeStatus_withStaleVersion_isConflict() throws Exception {
        UUID id = insertRequest(tenant, "IN_PROGRESS");
        jdbcTemplate.update("UPDATE requests SET version = 1 WHERE id = ?", id);

        changeStatus(id, operatorToken, body("DONE", null, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        assertThat(request(id)).containsEntry("status", "IN_PROGRESS");
        assertThat(history(id)).isEmpty();
    }

    /** Заявка другого арендатора и удалённая заявка — «не найдено», а не «запрещено». */
    @Test
    void changeStatus_ofOtherTenantOrDeletedRequest_isNotFound() throws Exception {
        AuthTestFixture.TestTenant other = insertTenant();
        UUID foreign = insertRequest(other, "NEW");
        UUID deleted = insertRequest(tenant, "NEW");
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);

        for (UUID id : List.of(foreign, deleted)) {
            changeStatus(id, operatorToken, body("CONTACTED", null, 0))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            assertThat(request(id)).containsEntry("status", "NEW");
            assertThat(history(id)).isEmpty();
        }
    }

    /** Без статуса или версии, с неизвестным статусом — ошибка валидации, а не 500. */
    @Test
    void changeStatus_withInvalidBody_isValidationError() throws Exception {
        UUID id = insertRequest(tenant, "NEW");

        changeStatus(id, operatorToken, "{\"status\":\"CONTACTED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        changeStatus(id, operatorToken, "{\"status\":\"CLOSED\",\"version\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(history(id)).isEmpty();
    }

    /**
     * История защищена и в обход приложения: запись нельзя изменить или удалить, а отклонение без
     * причины не записывается.
     */
    @Test
    void history_inDatabase_isReadOnlyAndRequiresRejectionReason() throws Exception {
        UUID id = insertRequest(tenant, "NEW");
        changeStatus(id, operatorToken, body("CONTACTED", null, 0)).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE request_status_history SET comment = 'правка' WHERE request_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM request_status_history WHERE request_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO request_status_history (id,"
                + " tenant_id, request_id, from_status, to_status, author_id, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'CONTACTED', 'REJECTED', ?, now(), now())",
                UUID.randomUUID(), tenant.id(), id, operatorId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(history(id)).hasSize(1);
    }

    private ResultActions changeStatus(UUID requestId, String accessToken, String json)
            throws Exception {
        return mockMvc.perform(put("/admin/requests/{id}/status", requestId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String body(String status, String comment, long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (comment != null) {
            body.put("comment", comment);
        }
        body.put("version", version);
        return objectMapper.writeValueAsString(body);
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
                "status-" + UUID.randomUUID().toString().replace("-", ""));
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

    private UUID insertRequest(AuthTestFixture.TestTenant tenantOfRequest, String status) {
        return insertRequest(tenantOfRequest, status, UUID.randomUUID());
    }

    private UUID insertRequest(AuthTestFixture.TestTenant tenantOfRequest, String status,
            UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, created_at, updated_at) VALUES (?, ?, 'Выгрузка за квартал',"
                + " 'Нужен отчёт', ?, ?, now(), now())", id, tenantOfRequest.id(), status,
                authorId);
        return id;
    }

    private double closedCount(String outcome) {
        return registry.get(RequestSlaMetrics.CLOSED)
                .tag(RequestSlaMetrics.OUTCOME, outcome)
                .tag(RequestSlaMetrics.TENANT, tenant.id().toString())
                .counter()
                .count();
    }

    private Map<String, Object> request(UUID id) {
        return jdbcTemplate.queryForMap("SELECT status, version FROM requests WHERE id = ?", id);
    }

    private List<Map<String, Object>> history(UUID requestId) {
        return jdbcTemplate.queryForList("SELECT tenant_id, from_status, to_status, author_id,"
                + " comment FROM request_status_history WHERE request_id = ?"
                + " ORDER BY created_at, id", requestId);
    }
}

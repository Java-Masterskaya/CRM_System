package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
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
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Письмо автору о смене статуса (T-070) — по HTTP, с настоящими токенами: оператор переводит
 * заявку клиента, а тест смотрит, какие события о ней легли в outbox. Доставку письма через очередь
 * и почту проверяет {@code ru.practicum.scenario.StatusChangeNotificationScenarioTest}.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestStatusNotificationIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String OPERATOR = "operator@example.com";
    private static final String CLIENT = "client@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private UUID clientId;
    private String operatorToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = authTestFixture.createTenant(
                "notify-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, tenant.id());
        insertUser(OPERATOR, "OPERATOR");
        clientId = insertUser(CLIENT, "CLIENT");
        jdbcTemplate.update("UPDATE users SET name = 'Анна Петрова' WHERE id = ?", clientId);
        operatorToken = login(OPERATOR);
    }

    /**
     * DoD: после смены статуса автор получает письмо с прежним и новым статусом. Письмо легло в
     * очередь в той же транзакции и ждёт отправки; статусы — названиями для клиента.
     */
    @Test
    void changeStatus_writesLetterToAuthorWithOldAndNewStatus() throws Exception {
        UUID id = insertRequest("NEW");

        changeStatus(id, "CONTACTED", null, 0).andExpect(status().isOk());

        assertThat(letters(id)).singleElement().satisfies(letter -> assertThat(letter)
                .containsEntry("tenant_id", tenant.id())
                .containsEntry("status", "NEW")
                .containsEntry("recipient", CLIENT)
                .containsEntry("type", "REQUEST_STATUS_CHANGED")
                .containsEntry("recipient_name", "Анна Петрова")
                .containsEntry("old_status", "Получена")
                .containsEntry("new_status", "Детали уточнены"));
    }

    /**
     * DoD: каждый переход порождает ровно одно уведомление. Отклонённый перевод письма не
     * порождает.
     */
    @Test
    void changeStatus_eachTransitionWritesExactlyOneLetter() throws Exception {
        UUID id = insertRequest("NEW");
        changeStatus(id, "CONTACTED", null, 0).andExpect(status().isOk());
        changeStatus(id, "IN_PROGRESS", null, 1).andExpect(status().isOk());
        changeStatus(id, "ON_HOLD", null, 2).andExpect(status().isOk());

        changeStatus(id, "DONE", null, 3).andExpect(status().isConflict());

        assertThat(letters(id)).extracting(letter -> letter.get("old_status") + " → "
                + letter.get("new_status")).containsExactly(
                "Получена → Детали уточнены",
                "Детали уточнены → В работе",
                "В работе → Ждём информацию от вас");
    }

    /**
     * DoD: письмо не содержит внутренних комментариев и служебных пометок — комментарий к
     * переходу, даже причина отклонения, остаётся в истории и в письмо не попадает.
     */
    @Test
    void letter_containsNoTransitionComment() throws Exception {
        UUID id = insertRequest("CONTACTED");

        changeStatus(id, "REJECTED", "Внутренняя пометка: дубль заявки 17", 0)
                .andExpect(status().isOk());

        assertThat(letters(id)).singleElement().satisfies(letter -> {
            assertThat(letter).containsEntry("new_status", "Отклонена");
            assertThat((String) letter.get("payload")).doesNotContain("Внутренняя пометка",
                    "дубль заявки");
        });
    }

    /** Автор заблокирован — статус всё равно меняется, а писать некому. */
    @Test
    void changeStatus_whenAuthorBlocked_changesStatusWithoutLetter() throws Exception {
        UUID id = insertRequest("NEW");
        jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", clientId);

        changeStatus(id, "CONTACTED", null, 0).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM requests WHERE id = ?",
                String.class, id)).isEqualTo("CONTACTED");
        assertThat(letters(id)).isEmpty();
    }

    /**
     * Смена статуса, история и письмо — одна транзакция: сбой записи истории откатывает и письмо
     * (T-065: при откате бизнес-операции события в очереди нет).
     */
    @Test
    void changeStatus_whenHistoryWriteFails_leavesNoLetter() throws Exception {
        UUID id = insertRequest("IN_PROGRESS");
        jdbcTemplate.execute("CREATE FUNCTION fail_status_history_insert() RETURNS TRIGGER AS $$"
                + " BEGIN RAISE EXCEPTION 'сбой записи истории'; END; $$ LANGUAGE plpgsql");
        jdbcTemplate.execute("CREATE TRIGGER fail_status_history_insert BEFORE INSERT"
                + " ON request_status_history FOR EACH ROW"
                + " EXECUTE FUNCTION fail_status_history_insert()");
        try {
            changeStatus(id, "DONE", null, 0).andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_status_history_insert"
                    + " ON request_status_history");
            jdbcTemplate.execute("DROP FUNCTION fail_status_history_insert()");
        }

        assertThat(letters(id)).isEmpty();
    }

    /** Письма о заявке в очереди — по порядку записи. */
    private List<Map<String, Object>> letters(UUID requestId) {
        return jdbcTemplate.queryForList("SELECT tenant_id, status,"
                + " payload ->> 'recipientEmail' AS recipient,"
                + " payload ->> 'notificationType' AS type,"
                + " payload -> 'variables' ->> 'recipientName' AS recipient_name,"
                + " payload -> 'variables' ->> 'oldStatus' AS old_status,"
                + " payload -> 'variables' ->> 'newStatus' AS new_status,"
                + " payload::text AS payload"
                + " FROM outbox_events WHERE event_type = 'EMAIL_NOTIFICATION'"
                + " AND aggregate_type = 'REQUEST' AND aggregate_id = ?"
                + " ORDER BY created_at, id", requestId);
    }

    private ResultActions changeStatus(UUID requestId, String target, String comment,
            long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", target);
        if (comment != null) {
            body.put("comment", comment);
        }
        body.put("version", version);
        return mockMvc.perform(put("/admin/requests/{id}/status", requestId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Заявка клиента — автора, которому уходят письма. */
    private UUID insertRequest(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, created_at, updated_at) VALUES (?, ?, 'Выгрузка за квартал',"
                + " 'Нужен отчёт', ?, ?, now(), now())", id, tenant.id(), status, clientId);
        return id;
    }

    private UUID insertUser(String email, String roleCode) {
        UUID id = authTestFixture.createUser(tenant, email, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenant.id(), roleCode);
        return id;
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenant.slug(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}

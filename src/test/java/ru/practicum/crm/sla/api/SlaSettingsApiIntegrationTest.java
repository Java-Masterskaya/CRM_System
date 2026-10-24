package ru.practicum.crm.sla.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Настройки сроков по HTTP (T-055 — DoD #77, T-056 — DoD #78, T-057 — DoD #79), с настоящими
 * токенами: вход, затем запрос с access-токеном. Роли — по умолчанию для арендатора: право
 * {@code SLA_POLICY_MANAGE} есть только у администратора.
 *
 * <p>Правила маршрутов в этом контексте выключены (флаг авторизации), их проверяет
 * {@code SecurityAuthorizationRulesTest}. Здесь — права на операции, изоляция арендаторов и сами
 * операции. Правила расчёта сроков проверяют интеграционные тесты сервисов пакета {@code sla}.
 *
 * <p>Типы заявок создаются запросами SQL: пакет сроков не зависит от пакета заявок.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class SlaSettingsApiIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String ADMIN = "admin@example.com";
    private static final String OPERATOR = "operator@example.com";
    private static final String CLIENT = "client@example.com";
    private static final String POLICIES = "/admin/sla-policies";
    private static final String WORKING_HOURS = "/admin/calendar/working-hours";
    private static final String HOLIDAYS = "/admin/calendar/holidays";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private UUID typeId;
    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        typeId = insertType(tenant);
        insertUser(tenant, ADMIN, "ADMIN");
        insertUser(tenant, OPERATOR, "OPERATOR");
        adminToken = login(tenant, ADMIN);
        operatorToken = login(tenant, OPERATOR);
    }

    // ---- Политики сроков (T-055) ----

    /**
     * DoD: администратор создаёт и просматривает политики своего арендатора — для пары и по
     * умолчанию. Список — в порядке создания.
     */
    @Test
    void policies_createdForPairAndByDefault_areListedInCreationOrder() throws Exception {
        String pairId = createdId(POLICIES, adminToken, policy(typeId, "URGENT", 60, 240));
        String defaultId = createdId(POLICIES, adminToken, policy(null, null, 240, 960));

        call(get(POLICIES), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(pairId))
                .andExpect(jsonPath("$.content[0].typeId").value(typeId.toString()))
                .andExpect(jsonPath("$.content[0].priority").value("URGENT"))
                .andExpect(jsonPath("$.content[0].defaultPolicy").value(false))
                .andExpect(jsonPath("$.content[0].firstResponseMinutes").value(60))
                .andExpect(jsonPath("$.content[0].resolutionMinutes").value(240))
                .andExpect(jsonPath("$.content[1].id").value(defaultId))
                .andExpect(jsonPath("$.content[1].defaultPolicy").value(true))
                .andExpect(jsonPath("$.content[1].typeId").value(nullValue()));
    }

    /** DoD: вторая политика на ту же пару отклоняется; вторая политика по умолчанию — тоже. */
    @Test
    void policy_forTakenPairOrSecondDefault_isConflict() throws Exception {
        createdId(POLICIES, adminToken, policy(typeId, "HIGH", 60, 240));
        createdId(POLICIES, adminToken, policy(null, null, 240, 960));

        send(post(POLICIES), adminToken, policy(typeId, "HIGH", 30, 120))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
        send(post(POLICIES), adminToken, policy(null, null, 30, 120))
                .andExpect(status().isConflict());
        assertThat(policyCount()).isEqualTo(2);
    }

    /** DoD: срок первого ответа больше срока решения отклоняется с указанием поля. */
    @Test
    void policy_withFirstResponseAfterResolution_isValidationErrorOnField() throws Exception {
        send(post(POLICIES), adminToken, policy(typeId, "HIGH", 300, 120))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/firstResponseMinutes"));
        assertThat(policyCount()).isZero();
    }

    /**
     * Без срока, без приоритета при заданном типе или с типом чужого арендатора политику не
     * создать — ошибка указывает поле.
     */
    @Test
    void policy_withMissingOrForeignFields_isValidationErrorOnField() throws Exception {
        UUID foreignType = insertType(insertTenant());

        send(post(POLICIES), adminToken, policy(typeId, "HIGH", 60, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/resolutionMinutes"));
        send(post(POLICIES), adminToken, policy(typeId, null, 60, 240))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/priority"));
        send(post(POLICIES), adminToken, policy(foreignType, "HIGH", 60, 240))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/typeId"));
        assertThat(policyCount()).isZero();
    }

    /** DoD: администратор изменяет сроки своей политики; чужой политики для него нет. */
    @Test
    void policyTerms_changedByAdmin_areSavedButForeignPolicyIsNotFound() throws Exception {
        String id = createdId(POLICIES, adminToken, policy(typeId, "HIGH", 60, 240));
        AuthTestFixture.TestTenant other = insertTenant();
        insertUser(other, ADMIN, "ADMIN");
        String foreignId = createdId(POLICIES, login(other, ADMIN),
                policy(null, null, 240, 960));

        send(put(POLICIES + "/" + id), adminToken, terms(30, 120))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponseMinutes").value(30))
                .andExpect(jsonPath("$.resolutionMinutes").value(120))
                .andExpect(jsonPath("$.priority").value("HIGH"));
        send(put(POLICIES + "/" + foreignId), adminToken, terms(30, 120))
                .andExpect(status().isNotFound());

        call(get(POLICIES), adminToken)
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT first_response_minutes FROM sla_policies WHERE id = ?::uuid",
                Integer.class, foreignId)).isEqualTo(240);
    }

    /** DoD: оператор и клиент не могут изменять политики — и не видят их. */
    @Test
    void policies_forOperator_areForbidden() throws Exception {
        call(get(POLICIES), operatorToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        send(post(POLICIES), operatorToken, policy(null, null, 240, 960))
                .andExpect(status().isForbidden());
        assertThat(policyCount()).isZero();
    }

    // ---- Рабочий календарь (T-056) ----

    /** DoD: администратор меняет календарь и видит его — по порядку, с понедельника. */
    @Test
    void workingHours_replacedByAdmin_areReturnedFromMonday() throws Exception {
        send(put(WORKING_HOURS), adminToken, week(
                day("FRIDAY", "10:00", "16:00"), day("MONDAY", "09:00", "18:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value("MONDAY"));

        call(get(WORKING_HOURS), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.days[0].start").value(startsWith("09:00")))
                .andExpect(jsonPath("$.days[1].dayOfWeek").value("FRIDAY"))
                .andExpect(jsonPath("$.days[1].end").value(startsWith("16:00")));
    }

    /**
     * DoD: интервал с началом позже конца отклоняется с указанием поля; повтор дня недели и
     * неделя без рабочих дней — тоже. Отказ не меняет календарь.
     */
    @Test
    void workingHours_invalid_areRejectedWithFieldAndCalendarKept() throws Exception {
        send(put(WORKING_HOURS), adminToken, week(day("MONDAY", "09:00", "18:00")))
                .andExpect(status().isOk());

        send(put(WORKING_HOURS), adminToken, week(day("TUESDAY", "18:00", "09:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/days/0/end"));
        send(put(WORKING_HOURS), adminToken, week(
                day("TUESDAY", "09:00", "18:00"), day("TUESDAY", "10:00", "12:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/days/1/dayOfWeek"));
        send(put(WORKING_HOURS), adminToken, week())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/days"));

        call(get(WORKING_HOURS), adminToken)
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value("MONDAY"));
    }

    /** DoD: администратор видит и меняет календарь только своего арендатора. */
    @Test
    void workingHours_ofAnotherTenant_areNotChanged() throws Exception {
        AuthTestFixture.TestTenant other = insertTenant();
        insertUser(other, ADMIN, "ADMIN");
        String otherAdmin = login(other, ADMIN);
        send(put(WORKING_HOURS), otherAdmin, week(day("SUNDAY", "10:00", "14:00")))
                .andExpect(status().isOk());

        send(put(WORKING_HOURS), adminToken, week(day("MONDAY", "09:00", "18:00")))
                .andExpect(status().isOk());

        call(get(WORKING_HOURS), otherAdmin)
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value("SUNDAY"));
    }

    @Test
    void workingHours_forOperator_areForbidden() throws Exception {
        call(get(WORKING_HOURS), operatorToken).andExpect(status().isForbidden());
        send(put(WORKING_HOURS), operatorToken, week(day("MONDAY", "09:00", "18:00")))
                .andExpect(status().isForbidden());
    }

    // ---- Нерабочие дни (T-057) ----

    /**
     * DoD: администратор добавляет, просматривает и удаляет даты. Нерабочий день — без часов,
     * рабочий (перенос) — с часами; список — по порядку дат.
     */
    @Test
    void holidays_addedListedAndDeletedByAdmin() throws Exception {
        String dayOff = createdId(HOLIDAYS, adminToken,
                holiday("2027-01-07", "Рождество", false, null, null));
        String workingDay = createdId(HOLIDAYS, adminToken,
                holiday("2027-01-02", "Перенос с субботы", true, "10:00", "15:00"));

        call(get(HOLIDAYS), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(workingDay))
                .andExpect(jsonPath("$.content[0].date").value("2027-01-02"))
                .andExpect(jsonPath("$.content[0].working").value(true))
                .andExpect(jsonPath("$.content[0].start").value(startsWith("10:00")))
                .andExpect(jsonPath("$.content[1].id").value(dayOff))
                .andExpect(jsonPath("$.content[1].working").value(false))
                .andExpect(jsonPath("$.content[1].start").value(nullValue()));

        call(delete(HOLIDAYS + "/" + dayOff), adminToken)
                .andExpect(status().isNoContent());
        call(get(HOLIDAYS), adminToken)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(workingDay));
    }

    /** DoD: повторное добавление той же даты отклоняется. */
    @Test
    void holiday_forTakenDate_isConflict() throws Exception {
        createdId(HOLIDAYS, adminToken, holiday("2027-01-07", "Рождество", false, null, null));

        send(post(HOLIDAYS), adminToken,
                holiday("2027-01-07", "Ещё раз", true, "10:00", "15:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
    }

    /**
     * Часы у нерабочего дня — ошибка в запросе, а не молча отброшенные поля; у рабочего дня без
     * конца — ошибка в поле {@code end}.
     */
    @Test
    void holiday_withWrongHours_isValidationErrorOnField() throws Exception {
        send(post(HOLIDAYS), adminToken, holiday("2027-01-07", "Рождество", false, "10:00", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/start"));
        send(post(HOLIDAYS), adminToken, holiday("2027-01-02", "Перенос", true, "10:00", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/end"));
        call(get(HOLIDAYS), adminToken).andExpect(jsonPath("$.totalElements").value(0));
    }

    /** DoD: даты одного арендатора не трогаются из другого — удалить чужую нельзя. */
    @Test
    void holiday_ofAnotherTenant_isInvisibleAndNotFound() throws Exception {
        AuthTestFixture.TestTenant other = insertTenant();
        insertUser(other, ADMIN, "ADMIN");
        String foreignId = createdId(HOLIDAYS, login(other, ADMIN),
                holiday("2027-01-07", "Рождество", false, null, null));

        call(get(HOLIDAYS), adminToken).andExpect(jsonPath("$.totalElements").value(0));
        call(delete(HOLIDAYS + "/" + foreignId), adminToken).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM holidays WHERE id = ?::uuid", Integer.class, foreignId))
                .isEqualTo(1);
    }

    @Test
    void holidays_forOperator_areForbidden() throws Exception {
        call(get(HOLIDAYS), operatorToken).andExpect(status().isForbidden());
        send(post(HOLIDAYS), operatorToken, holiday("2027-01-07", "Рождество", false, null, null))
                .andExpect(status().isForbidden());
    }

    // ---- Вместе ----

    /**
     * Настроенные через API неделя и политика сразу работают: новая заявка клиента получает оба
     * срока. До этой задачи их можно было задать только запросом к базе.
     */
    @Test
    void newRequest_afterSettingsConfiguredViaApi_getsDeadlines() throws Exception {
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'UTC', now(), now())", tenant.id());
        insertUser(tenant, CLIENT, "CLIENT");
        String clientToken = login(tenant, CLIENT);
        send(put(WORKING_HOURS), adminToken, week(
                day("MONDAY", "00:00", "23:59"), day("TUESDAY", "00:00", "23:59"),
                day("WEDNESDAY", "00:00", "23:59"), day("THURSDAY", "00:00", "23:59"),
                day("FRIDAY", "00:00", "23:59"), day("SATURDAY", "00:00", "23:59"),
                day("SUNDAY", "00:00", "23:59")))
                .andExpect(status().isOk());
        createdId(POLICIES, adminToken, policy(null, null, 60, 240));

        call(post("/client/requests").header("Idempotency-Key", "after-sla-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "typeId", typeId.toString(),
                        "subject", "Выгрузка за квартал",
                        "description", "Нужен отчёт"))), clientToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstResponseDueAt").isNotEmpty())
                .andExpect(jsonPath("$.resolutionDueAt").isNotEmpty());
    }

    private String createdId(String path, String token, String json) throws Exception {
        String response = send(post(path), token, json)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String json)
            throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(json), token);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String accessToken)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    /** Тело политики; {@code null} передаётся явно, как его прислал бы клиент API. */
    private String policy(UUID type, String priority, Integer firstResponse, Integer resolution)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", type);
        body.put("priority", priority);
        body.put("firstResponseMinutes", firstResponse);
        body.put("resolutionMinutes", resolution);
        return objectMapper.writeValueAsString(body);
    }

    private String terms(int firstResponse, int resolution) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "firstResponseMinutes", firstResponse, "resolutionMinutes", resolution));
    }

    private String week(Map<?, ?>... days) throws Exception {
        return objectMapper.writeValueAsString(Map.of("days", List.of(days)));
    }

    private static Map<String, String> day(String dayOfWeek, String start, String end) {
        return Map.of("dayOfWeek", dayOfWeek, "start", start, "end", end);
    }

    private String holiday(String date, String description, boolean working, String start,
            String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date);
        body.put("description", description);
        body.put("working", working);
        body.put("start", start);
        body.put("end", end);
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
                "sla-" + UUID.randomUUID().toString().replace("-", ""));
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

    private UUID insertType(AuthTestFixture.TestTenant tenantOfType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, active, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка', TRUE, now(), now())",
                id, tenantOfType.id());
        return id;
    }

    private int policyCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sla_policies WHERE tenant_id = ?", Integer.class,
                tenant.id());
        return count == null ? 0 : count;
    }
}

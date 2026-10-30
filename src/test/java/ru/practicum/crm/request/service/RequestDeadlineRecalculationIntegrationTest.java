package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Пересчёт сроков при смене приоритета или типа (T-062, DoD #84) и снятие признаков просрочки и
 * эскалации при сдвиге срока (#200) — через правку заявки по HTTP, с настоящими токенами, базой
 * и фоновой проверкой просрочки.
 *
 * <p>Календарь арендатора — круглые сутки по UTC, чтобы минуты политики были минутами на часах.
 * Политики: по умолчанию — 60 и 120 минут, для пары «тип + LOW» — 600 и 1200, для пары
 * «тип + URGENT» — 10 и 30. Заявки и политики создаются запросами SQL.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestDeadlineRecalculationIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String ADMIN = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OverdueRequestDetector detector;

    private AuthTestFixture.TestTenant tenant;
    private UUID typeId;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        tenant = authTestFixture.createTenant(
                "recalc-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, tenant.id());
        UUID adminId = authTestFixture.createUser(tenant, ADMIN, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = 'ADMIN'",
                adminId, tenant.id());
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'UTC', now(), now())", tenant.id());
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
                "SATURDAY", "SUNDAY")) {
            jdbcTemplate.update("INSERT INTO working_hours (id, tenant_id, day_of_week,"
                    + " start_time, end_time, created_at, updated_at) VALUES (?, ?, ?,"
                    + " TIME '00:00', TIME '23:59:59', now(), now())",
                    UUID.randomUUID(), tenant.id(), day);
        }
        typeId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, active, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка', TRUE, now(), now())",
                typeId, tenant.id());
        insertPolicy(null, null, 60, 120);
        insertPolicy(typeId, "LOW", 600, 1200);
        insertPolicy(typeId, "URGENT", 10, 30);
        adminToken = login();
    }

    /** DoD #84: повышение приоритета сокращает срок решения по новой политике. */
    @Test
    void edit_raisingPriority_shortensResolutionDeadline() throws Exception {
        UUID id = insertRequest(minutesAgo(5), minutesFromNow(55), minutesFromNow(115), false);
        Instant before = resolutionDueAt(id);

        edit(id, "URGENT").andExpect(status().isOk());

        assertThat(resolutionDueAt(id)).isBefore(before);
    }

    /**
     * DoD #84 и #200: заявка была просрочена по обоим срокам и эскалирована; после смены приоритета
     * оба срока впереди — признаки просрочки и отметки эскалации сняты, а в журнале аудита —
     * прежние и новые значения обоих сроков.
     */
    @Test
    void edit_movingDeadlinesAhead_reopensOverdueAndEscalation() throws Exception {
        UUID id = insertRequest(minutesAgo(120), minutesAgo(60), minutesAgo(1), true);

        edit(id, "LOW")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponseOverdue").value(false))
                .andExpect(jsonPath("$.resolutionOverdue").value(false))
                .andExpect(jsonPath("$.overdue").value(false));

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("first_response_overdue", false)
                .containsEntry("resolution_overdue", false)
                .containsEntry("first_response_escalated", false)
                .containsEntry("resolution_escalated", false);
        assertThat((Timestamp) row.get("first_response_due_at"))
                .isAfter(Timestamp.from(Instant.now()));
        assertThat(auditedFields(id))
                .contains("PRIORITY", "FIRST_RESPONSE_DUE_AT", "RESOLUTION_DUE_AT");
    }

    /**
     * DoD #200: когда перенесённый срок истечёт, фоновая проверка снова помечает заявку и пишет
     * ровно одно письмо на срок — повторный проход писем не добавляет.
     */
    @Test
    void movedDeadline_whenItExpires_isEscalatedAgainExactlyOnce() throws Exception {
        UUID id = insertRequest(minutesAgo(120), minutesAgo(60), minutesAgo(1), true);
        edit(id, "LOW").andExpect(status().isOk());
        jdbcTemplate.update("UPDATE requests SET first_response_due_at = now() - interval '1"
                + " minute', resolution_due_at = now() - interval '1 minute' WHERE id = ?", id);

        detector.detect();
        detector.detect();

        assertThat(row(id)).containsEntry("first_response_overdue", true)
                .containsEntry("resolution_overdue", true)
                .containsEntry("first_response_escalated", true)
                .containsEntry("resolution_escalated", true);
        assertThat(lettersAbout(id)).as("по письму администратору на каждый срок").isEqualTo(2);
    }

    /**
     * Новый срок тоже уже прошёл: нарушение остаётся нарушением — признаки и отметки эскалации не
     * снимаются, и повторных писем нет.
     */
    @Test
    void edit_whenNewDeadlinesAlsoPassed_keepsOverdueAndDoesNotEscalateAgain() throws Exception {
        UUID id = insertRequest(minutesAgo(120), minutesAgo(60), minutesAgo(1), true);

        edit(id, "URGENT").andExpect(status().isOk());
        detector.detect();

        assertThat(row(id)).containsEntry("first_response_overdue", true)
                .containsEntry("resolution_overdue", true)
                .containsEntry("first_response_escalated", true)
                .containsEntry("resolution_escalated", true);
        assertThat(lettersAbout(id)).isZero();
    }

    /** Приоритет и тип прежние — сроки не пересчитываются и в журнал не попадают. */
    @Test
    void edit_keepingPriorityAndType_leavesDeadlinesUntouched() throws Exception {
        UUID id = insertRequest(minutesAgo(120), minutesAgo(60), minutesAgo(1), true);
        Instant before = resolutionDueAt(id);

        edit(id, "NORMAL").andExpect(status().isOk());

        assertThat(resolutionDueAt(id)).isEqualTo(before);
        assertThat(row(id)).containsEntry("resolution_overdue", true)
                .containsEntry("resolution_escalated", true);
        assertThat(auditedFields(id)).containsExactly("DESCRIPTION");
    }

    private ResultActions edit(UUID id, String priority) throws Exception {
        Long version = jdbcTemplate.queryForObject("SELECT version FROM requests WHERE id = ?",
                Long.class, id);
        return mockMvc.perform(put("/admin/requests/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "version", version,
                        "priority", priority,
                        "typeId", typeId.toString(),
                        "description", "Нужен отчёт с разбивкой"))));
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenant.slug(),
                                "email", ADMIN,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private void insertPolicy(UUID type, String priority, int firstResponse, int resolution) {
        jdbcTemplate.update("INSERT INTO sla_policies (id, tenant_id, type_id, priority,"
                + " first_response_minutes, resolution_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), tenant.id(), type, priority, firstResponse, resolution);
    }

    /** Заявка в {@code NEW} с приоритетом {@code NORMAL}; просроченная — сразу и эскалирована. */
    private UUID insertRequest(Instant createdAt, Instant firstResponseDueAt,
            Instant resolutionDueAt, boolean overdueAndEscalated) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, type_id, subject, description,"
                + " status, author_id, created_at, updated_at, first_response_due_at,"
                + " resolution_due_at, first_response_overdue, resolution_overdue,"
                + " first_response_escalated, resolution_escalated) VALUES (?, ?, ?,"
                + " 'Выгрузка за квартал', 'Нужен отчёт', 'NEW', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant.id(), typeId, UUID.randomUUID(), Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(firstResponseDueAt),
                Timestamp.from(resolutionDueAt), overdueAndEscalated, overdueAndEscalated,
                overdueAndEscalated, overdueAndEscalated);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT first_response_due_at, resolution_due_at,"
                + " first_response_overdue, resolution_overdue, first_response_escalated,"
                + " resolution_escalated FROM requests WHERE id = ?", id);
    }

    private Instant resolutionDueAt(UUID id) {
        Timestamp dueAt = jdbcTemplate.queryForObject(
                "SELECT resolution_due_at FROM requests WHERE id = ?", Timestamp.class, id);
        return dueAt == null ? null : dueAt.toInstant();
    }

    private List<String> auditedFields(UUID id) {
        return jdbcTemplate.queryForList("SELECT field FROM request_audit_log WHERE request_id = ?",
                String.class, id);
    }

    private int lettersAbout(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minusSeconds(minutes * 60);
    }

    private static Instant minutesFromNow(long minutes) {
        return Instant.now().plusSeconds(minutes * 60);
    }
}

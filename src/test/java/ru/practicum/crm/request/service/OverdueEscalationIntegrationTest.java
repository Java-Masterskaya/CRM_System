package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.request.service.OverdueEscalation.Deadline;

/**
 * Эскалация просроченных заявок на реальной базе (T-061): кто получает письма, повторные проходы,
 * два срока, заявка без исполнителя, одна транзакция писем и отметки, два экземпляра приложения,
 * планы выборок и миграция на базе, где просроченные заявки уже есть.
 *
 * <p>Арендатор получает роли и права по умолчанию: право {@code SLA_POLICY_MANAGE} в них есть
 * только у администратора. Письма проверяются по записям в outbox — до отправки дело не доходит.
 */
class OverdueEscalationIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610151010";
    private static final String STATUS_INDEX_MIGRATION_VERSION = "202610161500";
    private static final String PREVIOUS_MIGRATION = "202610122335";
    private static final ZoneId TENANT_ZONE = ZoneId.of("Europe/Moscow");
    private static final String ADMIN = "admin@example.com";
    private static final String OPERATOR = "operator@example.com";

    @Autowired
    private OverdueRequestDetector detector;

    @Autowired
    private OverdueEscalation escalation;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenant;
    private UUID operator;
    private Instant hourAgo;
    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        tenant = insertTenant();
        grantRole(tenant, insertUser(tenant, ADMIN, "Анна"), "ADMIN");
        operator = insertUser(tenant, OPERATOR, "Олег");
        grantRole(tenant, operator, "OPERATOR");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        hourAgo = now.minus(Duration.ofHours(1));
        tomorrow = now.plus(Duration.ofDays(1));
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        for (String version : List.of(MIGRATION_VERSION, STATUS_INDEX_MIGRATION_VERSION)) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT success FROM flyway_schema_history WHERE version = ?",
                    Boolean.class, version)).as("миграция %s", version).isTrue();
        }
    }

    /**
     * DoD: при первом обнаружении просрочки исполнитель и администратор получают уведомление.
     * Письмо — о нарушенном сроке, со сроком по часам арендатора; событие ссылается на заявку.
     */
    @Test
    void detect_whenDeadlineIsFirstFoundMissed_notifiesAssigneeAndAdmin() {
        UUID id = insertRequest("IN_PROGRESS", operator, tomorrow, hourAgo);

        detector.detect();

        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "решения", id),
                new Email(OPERATOR, "решения", id));
        EmailNotification email = notificationTo(ADMIN);
        assertThat(email.type()).isEqualTo(NotificationType.SLA_BREACHED);
        assertThat(email.variables())
                .containsEntry("recipientName", "Анна")
                .containsEntry("requestId", id.toString())
                .containsEntry("requestSubject", "Выгрузка за квартал")
                .containsEntry("dueAt", hourAgo.atZone(TENANT_ZONE)
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                        + " (Europe/Moscow)");
        assertThat(flags(id)).containsEntry("resolution_escalated", true)
                .containsEntry("first_response_escalated", false);
    }

    /**
     * DoD: три прохода — ровно одно уведомление каждому получателю. Эскалация не меняет версию
     * заявки: администратор, открывший заявку, не получит из-за писем «данные устарели».
     */
    @Test
    void detect_threePasses_notifyEachRecipientExactlyOnce() {
        UUID id = insertRequest("IN_PROGRESS", operator, tomorrow, hourAgo);

        detector.detect();
        final long versionAfterFirstPass = (Long) flags(id).get("version");
        detector.detect();
        detector.detect();

        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "решения", id),
                new Email(OPERATOR, "решения", id));
        assertThat(flags(id)).containsEntry("version", versionAfterFirstPass);
    }

    /** DoD: истекли оба срока — два отдельных события каждому получателю, по одному на срок. */
    @Test
    void detect_whenBothDeadlinesMissed_sendsSeparateEventPerDeadline() {
        UUID id = insertRequest("NEW", operator, hourAgo, hourAgo);

        detector.detect();
        detector.detect();

        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "первого ответа", id),
                new Email(ADMIN, "решения", id),
                new Email(OPERATOR, "первого ответа", id),
                new Email(OPERATOR, "решения", id));
        assertThat(flags(id)).containsEntry("first_response_escalated", true)
                .containsEntry("resolution_escalated", true);
    }

    /** Сроки истекают в разные проходы — и письма уходят в разные проходы, без повторов. */
    @Test
    void detect_whenSecondDeadlineMissedLater_sendsOnlyItsEvent() {
        UUID id = insertRequest("NEW", null, hourAgo, tomorrow);
        detector.detect();
        jdbcTemplate.update("UPDATE requests SET resolution_due_at = ?::timestamptz WHERE id = ?",
                hourAgo.toString(), id);

        detector.detect();

        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "первого ответа", id),
                new Email(ADMIN, "решения", id));
    }

    /** DoD: заявка без исполнителя эскалируется администраторам. */
    @Test
    void detect_whenRequestHasNoAssignee_notifiesAdminsOnly() {
        UUID id = insertRequest("NEW", null, hourAgo, tomorrow);

        detector.detect();

        assertThat(emails()).containsExactly(new Email(ADMIN, "первого ответа", id));
    }

    /**
     * Администраторы — только действующие и только арендатора заявки; оператор без права
     * {@code SLA_POLICY_MANAGE} писем не получает, пока он не исполнитель.
     */
    @Test
    void detect_notifiesOnlyActiveAdminsOfRequestTenant() {
        grantRole(tenant, insertUser(tenant, "second-admin@example.com", "Вера"), "ADMIN");
        UUID blocked = insertUser(tenant, "blocked@example.com", "Борис");
        grantRole(tenant, blocked, "ADMIN");
        jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", blocked);
        UUID deleted = insertUser(tenant, "deleted@example.com", "Дина");
        grantRole(tenant, deleted, "ADMIN");
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted);
        UUID otherTenant = insertTenant();
        grantRole(otherTenant, insertUser(otherTenant, "other-admin@example.com", "Чужой"),
                "ADMIN");
        UUID id = insertRequest("IN_PROGRESS", null, null, hourAgo);

        detector.detect();

        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "решения", id),
                new Email("second-admin@example.com", "решения", id));
    }

    /**
     * Ревью #198: заявку закрыли, ответили на неё или поставили на ожидание между пометкой
     * просрочки и эскалацией — например, проход прервался, и эскалация досталась следующему. По
     * сроку, который для её статуса уже не считается, письма нет. Закрытая заявка из выборок
     * выпадает совсем — её признаки эскалации так и не ставятся.
     */
    @Test
    void detect_whenRequestLeftCountedStatusBeforeEscalation_sendsNothingAboutIt() {
        Map<String, UUID> byStatus = new LinkedHashMap<>();
        for (String status : List.of("CONTACTED", "ON_HOLD", "DONE", "REJECTED", "CANCELLED")) {
            UUID id = insertRequest(status, null, hourAgo, hourAgo);
            jdbcTemplate.update("UPDATE requests SET first_response_overdue = TRUE,"
                    + " resolution_overdue = TRUE WHERE id = ?", id);
            byStatus.put(status, id);
        }

        detector.detect();

        assertThat(emails()).as("письмо только о сроке решения заявки, которая ещё в работе")
                .containsExactly(new Email(ADMIN, "решения", byStatus.get("CONTACTED")));
        for (String closed : List.of("DONE", "REJECTED", "CANCELLED")) {
            assertThat(flags(byStatus.get(closed))).as(closed)
                    .containsEntry("first_response_escalated", false)
                    .containsEntry("resolution_escalated", false);
        }
    }

    /**
     * Писать некому — заявка всё равно отмечается, иначе каждый проход выбирал бы её снова.
     * Исполнитель заблокирован, а администраторов у арендатора нет.
     */
    @Test
    void detect_whenNobodyToNotify_marksRequestEscalatedAnyway() {
        UUID lonelyTenant = insertTenant();
        UUID blockedAssignee = insertUser(lonelyTenant, "gone@example.com", "Гоша");
        jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", blockedAssignee);
        UUID id = insertRequest(lonelyTenant, "IN_PROGRESS", blockedAssignee, null, hourAgo);

        detector.detect();

        assertThat(emails()).isEmpty();
        assertThat(flags(id)).containsEntry("resolution_escalated", true);
    }

    /** Письма и отметка — одна транзакция: откат порции не оставляет ни того, ни другого. */
    @Test
    void escalateBatch_whenTransactionRollsBack_leavesNeitherEmailsNorMark() {
        UUID id = insertOverdueRequest(null);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(escalation.escalateBatch(Deadline.RESOLUTION, 10)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(emails()).isEmpty();
        assertThat(flags(id)).containsEntry("resolution_escalated", false);
    }

    /** Без транзакции письма записались бы отдельно от отметки — такой вызов отклоняется. */
    @Test
    void escalateBatch_outsideTransaction_isRejected() {
        assertThatThrownBy(() -> escalation.escalateBatch(Deadline.RESOLUTION, 10))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Два экземпляра приложения: пока один держит заявку, другой её пропускает, а не ждёт; письма
     * по ней разошлёт следующий проход — один раз.
     */
    @Test
    void detect_whenAnotherInstanceHoldsRequest_skipsItInsteadOfWaiting() throws Exception {
        UUID held = insertOverdueRequest(null);
        UUID free = insertOverdueRequest(null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            final Future<?> otherInstance = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.queryForList("SELECT id FROM requests WHERE id = ?"
                                + " FOR UPDATE", held);
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            detector.detect();
            release.countDown();
            otherInstance.get(10, TimeUnit.SECONDS);

            assertThat(emails()).containsExactly(new Email(ADMIN, "решения", free));
        }
        detector.detect();
        assertThat(emails()).containsExactlyInAnyOrder(
                new Email(ADMIN, "решения", free),
                new Email(ADMIN, "решения", held));
    }

    /**
     * Двадцать тысяч давно просроченных закрытых заявок: выборки эскалации идут по своим частичным
     * индексам, в которых таких заявок нет, — ни эскалированных, ни закрытых до эскалации.
     */
    @Test
    void escalationSelections_whenTableIsLarge_useTheirPartialIndexes() {
        // Половина — уже эскалированные, половина — закрытые без эскалации (случай из ревью
        // #198): ни те, ни другие в частичные индексы не попадают.
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, first_response_due_at, resolution_due_at, first_response_overdue,"
                + " resolution_overdue, first_response_escalated, resolution_escalated,"
                + " created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, 'Тема', 'Описание', 'DONE', gen_random_uuid(),"
                + " ?::timestamptz, ?::timestamptz, TRUE, TRUE, i % 2 = 0, i % 2 = 0, now(),"
                + " now() FROM generate_series(1, 20000) AS s(i)",
                tenant, hourAgo.toString(), hourAgo.toString());
        UUID waiting = insertRequest("NEW", null, hourAgo, hourAgo);
        jdbcTemplate.update("UPDATE requests SET first_response_overdue = TRUE,"
                + " resolution_overdue = TRUE WHERE id = ?", waiting);
        jdbcTemplate.execute("ANALYZE requests");

        assertThat(plan("SELECT * FROM requests WHERE status = 'NEW' AND first_response_overdue"
                + " AND NOT first_response_escalated AND NOT deleted"
                + " ORDER BY first_response_due_at LIMIT 500 FOR UPDATE SKIP LOCKED"))
                .as("выборка эскалации первого ответа")
                .contains("idx_requests_first_response_escalation").doesNotContain("Seq Scan");
        assertThat(plan("SELECT * FROM requests WHERE status IN ('NEW', 'CONTACTED',"
                + " 'IN_PROGRESS') AND resolution_overdue AND NOT resolution_escalated"
                + " AND NOT deleted ORDER BY resolution_due_at LIMIT 500 FOR UPDATE SKIP LOCKED"))
                .as("выборка эскалации решения")
                .contains("idx_requests_resolution_escalation").doesNotContain("Seq Scan");
    }

    /**
     * Миграция на базе, где просроченные заявки уже есть: они считаются эскалированными, иначе
     * первый проход после выкладки разослал бы письма о давно истёкших сроках. Миграции
     * прогоняются в отдельной схеме, чтобы не трогать общую базу тестов.
     */
    @Test
    void migration_onDatabaseWithOverdueRequests_marksThemEscalated() {
        String schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
        UUID oldTenant = UUID.randomUUID();
        Map<UUID, boolean[]> overdueFlags = Map.of(
                UUID.randomUUID(), new boolean[] {true, false},
                UUID.randomUUID(), new boolean[] {false, true},
                UUID.randomUUID(), new boolean[] {true, true},
                UUID.randomUUID(), new boolean[] {false, false});
        try {
            migrate(schema, PREVIOUS_MIGRATION);
            jdbcTemplate.update("INSERT INTO " + schema + ".tenants (id, name, slug, active,"
                    + " created_at, updated_at) VALUES (?, 'Был до T-061', ?, true, now(), now())",
                    oldTenant, slugOf(oldTenant));
            overdueFlags.forEach((id, overdue) -> jdbcTemplate.update("INSERT INTO " + schema
                    + ".requests (id, tenant_id, subject, description, status, author_id,"
                    + " first_response_overdue, resolution_overdue, created_at, updated_at)"
                    + " VALUES (?, ?, 'Тема', 'Описание', 'IN_PROGRESS', ?, ?, ?, now(), now())",
                    id, oldTenant, UUID.randomUUID(), overdue[0], overdue[1]));

            migrate(schema, null);

            overdueFlags.forEach((id, overdue) -> assertThat(jdbcTemplate.queryForMap(
                    "SELECT first_response_escalated, resolution_escalated FROM " + schema
                            + ".requests WHERE id = ?", id))
                    .containsEntry("first_response_escalated", overdue[0])
                    .containsEntry("resolution_escalated", overdue[1]));
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Письмо в outbox: кому, о каком сроке и по какой заявке. */
    private record Email(String recipient, String deadlineName, UUID requestId) {
    }

    private List<Email> emails() {
        return jdbcTemplate.query("SELECT payload ->> 'recipientEmail',"
                + " payload -> 'variables' ->> 'deadlineName', aggregate_id FROM outbox_events"
                + " WHERE event_type = ? AND aggregate_type = 'REQUEST'",
                (rs, rowNum) -> new Email(rs.getString(1), rs.getString(2),
                        rs.getObject(3, UUID.class)),
                EmailNotification.EVENT_TYPE);
    }

    /** Письмо получателю в том виде, в каком его прочтёт отправитель почты. */
    private EmailNotification notificationTo(String recipient) {
        String json = jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_events"
                + " WHERE payload ->> 'recipientEmail' = ?", String.class, recipient);
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {});
            return EmailNotification.fromPayload(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> flags(UUID id) {
        return jdbcTemplate.queryForMap("SELECT first_response_escalated, resolution_escalated,"
                + " version FROM requests WHERE id = ?", id);
    }

    private String plan(String sql) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
    }

    /** Заявка, просрочку решения которой проход уже отметил, а письма ещё не разосланы. */
    private UUID insertOverdueRequest(UUID assignee) {
        UUID id = insertRequest("IN_PROGRESS", assignee, null, hourAgo);
        jdbcTemplate.update("UPDATE requests SET resolution_overdue = TRUE WHERE id = ?", id);
        return id;
    }

    private UUID insertRequest(String status, UUID assignee, Instant firstResponseDueAt,
            Instant resolutionDueAt) {
        return insertRequest(tenant, status, assignee, firstResponseDueAt, resolutionDueAt);
    }

    /**
     * Исполнитель и время назначения задаются вместе — этого требует ограничение
     * {@code requests_assignment_check} (T-048).
     */
    private UUID insertRequest(UUID tenantId, String status, UUID assignee,
            Instant firstResponseDueAt, Instant resolutionDueAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, assignee_id, assigned_at, first_response_due_at,"
                + " resolution_due_at, created_at, updated_at)"
                + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?, ?, ?::timestamptz,"
                + " ?::timestamptz, ?::timestamptz, now(), now())", id, tenantId, status,
                UUID.randomUUID(), assignee, assignee == null ? null : hourAgo.toString(),
                firstResponseDueAt == null ? null : firstResponseDueAt.toString(),
                resolutionDueAt == null ? null : resolutionDueAt.toString());
        return id;
    }

    /** Арендатор с поясом и с ролями и правами по умолчанию, как у нового арендатора. */
    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', ?, true, now(), now())", id, slugOf(id));
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, ?, now(), now())", id, TENANT_ZONE.getId());
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, id);
        return id;
    }

    private UUID insertUser(UUID tenantId, String email, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, email, name, password_hash)"
                + " VALUES (?, ?, ?, ?, 'hash')", id, tenantId, email, name);
        return id;
    }

    private void grantRole(UUID tenantId, UUID userId, String roleCode) {
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                userId, tenantId, roleCode);
    }

    /** Уникальный slug арендатора в формате из T-025: строчные латинские буквы, цифры и дефис. */
    private static String slugOf(UUID tenantId) {
        return "tenant-" + tenantId.toString().replace("-", "");
    }

    private static void migrate(String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Не дождались проверки");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}

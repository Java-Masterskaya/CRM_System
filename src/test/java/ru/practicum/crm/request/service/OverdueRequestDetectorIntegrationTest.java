package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.config.OverdueDetectionProperties;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Фоновая проверка просроченных заявок на реальной базе (T-060): какие заявки помечаются и
 * какие нет, повторный проход, остановка между порциями, два экземпляра приложения, фильтр
 * реестра, планы запросов и миграция на базе, где заявки уже есть.
 *
 * <p>Заявки создаются запросами SQL: так в одном тесте видны и сроки, и статус, и признаки.
 */
class OverdueRequestDetectorIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610090910";
    private static final String PREVIOUS_MIGRATION = "202610061800";

    @Autowired
    private OverdueRequestDetector detector;

    @Autowired
    private RequestRepository requests;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private Instant hourAgo;
    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant();
        tenantB = insertTenant();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        hourAgo = now.minus(Duration.ofHours(1));
        tomorrow = now.plus(Duration.ofDays(1));
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    /** DoD: заявка с истёкшим сроком получает признак просрочки, её статус остаётся прежним. */
    @Test
    void detect_whenBothDeadlinesMissedInNew_marksBothAndKeepsStatus() {
        UUID id = insertRequest(tenantA, "NEW", hourAgo, hourAgo);

        assertThat(detector.detect()).isEqualTo(2);

        assertThat(row(id)).containsEntry("first_response_overdue", true)
                .containsEntry("resolution_overdue", true)
                .containsEntry("status", "NEW")
                .containsEntry("version", 1L);
    }

    /** Первый ответ — переход из NEW в CONTACTED: после него этот срок уже не просрочивается. */
    @Test
    void detect_firstResponseDeadline_countsOnlyWhileRequestIsNew() {
        UUID contacted = insertRequest(tenantA, "CONTACTED", hourAgo, tomorrow);
        UUID inProgress = insertRequest(tenantA, "IN_PROGRESS", hourAgo, hourAgo);

        detector.detect();

        assertThat(row(contacted)).containsEntry("first_response_overdue", false)
                .containsEntry("resolution_overdue", false);
        assertThat(row(inProgress)).containsEntry("first_response_overdue", false)
                .containsEntry("resolution_overdue", true);
    }

    /** DoD: заявки в терминальных статусах и в статусе ожидания признак просрочки не получают. */
    @Test
    void detect_skipsTerminalWaitingAndDeletedRequests() {
        List<UUID> skipped = List.of(
                insertRequest(tenantA, "ON_HOLD", hourAgo, hourAgo),
                insertRequest(tenantA, "DONE", hourAgo, hourAgo),
                insertRequest(tenantA, "REJECTED", hourAgo, hourAgo),
                insertRequest(tenantA, "CANCELLED", hourAgo, hourAgo));
        UUID deleted = insertRequest(tenantA, "NEW", hourAgo, hourAgo);
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);

        assertThat(detector.detect()).isZero();

        for (UUID id : skipped) {
            assertThat(row(id)).containsEntry("first_response_overdue", false)
                    .containsEntry("resolution_overdue", false)
                    .containsEntry("version", 0L);
        }
        assertThat(row(deleted)).containsEntry("resolution_overdue", false);
    }

    @Test
    void detect_skipsDeadlinesNotYetPassedOrNotSet() {
        UUID future = insertRequest(tenantA, "NEW", tomorrow, tomorrow);
        UUID withoutDeadlines = insertRequest(tenantA, "NEW", null, null);

        assertThat(detector.detect()).isZero();

        assertThat(row(future)).containsEntry("first_response_overdue", false)
                .containsEntry("resolution_overdue", false);
        assertThat(row(withoutDeadlines)).containsEntry("first_response_overdue", false)
                .containsEntry("resolution_overdue", false);
    }

    /**
     * DoD: повторный запуск не изменяет уже помеченные заявки и не порождает новых записей в
     * журнале — ни версия, ни время изменения не трогаются, журнал аудита пуст.
     */
    @Test
    void detect_secondPass_changesNothing() {
        UUID id = insertRequest(tenantA, "NEW", hourAgo, hourAgo);
        detector.detect();
        Map<String, Object> afterFirstPass = row(id);

        assertThat(detector.detect()).isZero();

        assertThat(row(id)).isEqualTo(afterFirstPass);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM request_audit_log",
                Integer.class)).isZero();
    }

    /**
     * DoD: остановка приложения во время прохода не оставляет заявки наполовину обработанными.
     * Порция — одна транзакция: после остановки две заявки помечены целиком, три не тронуты
     * вовсе, и следующий проход доделывает остаток.
     */
    @Test
    void detect_whenStoppedBetweenBatches_leavesEveryRequestEitherDoneOrUntouched() {
        for (int minutes = 5; minutes >= 1; minutes--) {
            insertRequest(tenantA, "NEW", hourAgo.minus(Duration.ofMinutes(minutes)), null);
        }
        OverdueRequestDetector batchesOfTwo = new OverdueRequestDetector(requests,
                transactionManager, new OverdueDetectionProperties(2, Duration.ofMinutes(1)));
        AtomicInteger checks = new AtomicInteger();

        assertThat(batchesOfTwo.detect(() -> checks.incrementAndGet() > 1)).isEqualTo(2);

        assertThat(jdbcTemplate.query("SELECT first_response_overdue, version"
                + " FROM requests ORDER BY first_response_due_at",
                (rs, rowNum) -> rs.getBoolean(1) + "/" + rs.getLong(2)))
                .containsExactly("true/1", "true/1", "false/0", "false/0", "false/0");
        assertThat(batchesOfTwo.detect(() -> false)).isEqualTo(3);
    }

    /**
     * Два экземпляра приложения: пока один держит заявку, другой её пропускает, а не ждёт, и
     * помечает остальные; придержанную заявку пометит следующий проход.
     */
    @Test
    void detect_whenAnotherInstanceHoldsRequest_skipsItInsteadOfWaiting() throws Exception {
        UUID held = insertRequest(tenantA, "IN_PROGRESS", null, hourAgo);
        UUID free = insertRequest(tenantA, "IN_PROGRESS", null, hourAgo);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> otherInstance = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.queryForList("SELECT id FROM requests WHERE id = ?"
                                + " FOR UPDATE", held);
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            int marked = detector.detect();
            release.countDown();
            otherInstance.get(10, TimeUnit.SECONDS);

            assertThat(marked).isEqualTo(1);
            assertThat(row(free)).containsEntry("resolution_overdue", true);
            assertThat(row(held)).containsEntry("resolution_overdue", false);
        }
        assertThat(detector.detect()).isEqualTo(1);
        assertThat(row(held)).containsEntry("resolution_overdue", true);
    }

    /** DoD: просроченные заявки доступны отдельным фильтром реестра — только своего арендатора. */
    @Test
    void findOverdueByTenantId_returnsOverdueByEitherDeadlineOfThatTenantOnly() {
        final UUID byFirstResponse = insertRequest(tenantA, "NEW", hourAgo, tomorrow);
        final UUID byResolution = insertRequest(tenantA, "IN_PROGRESS", null, hourAgo);
        insertRequest(tenantA, "NEW", tomorrow, tomorrow);
        UUID deleted = insertRequest(tenantA, "NEW", hourAgo, hourAgo);
        insertRequest(tenantB, "NEW", hourAgo, hourAgo);
        detector.detect();
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);

        List<UUID> overdue = requests.findOverdueByTenantId(tenantA, PageRequest.of(0, 20))
                .map(Request::getId).getContent();

        assertThat(overdue).containsExactlyInAnyOrder(byFirstResponse, byResolution);
    }

    /**
     * Двадцать тысяч завершённых заявок с истёкшими сроками и полсотни будущих: обе выборки
     * проверки идут по своим частичным индексам, в которых завершённых заявок нет, а фильтр
     * реестра — по индексу просроченных.
     */
    @Test
    void selections_whenTableIsLarge_useTheirPartialIndexes() {
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, first_response_due_at, resolution_due_at, created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, 'Тема', 'Описание',"
                + " CASE WHEN i <= 50 THEN 'NEW' ELSE 'DONE' END, gen_random_uuid(),"
                + " CASE WHEN i <= 50 THEN ?::timestamptz ELSE ?::timestamptz END,"
                + " CASE WHEN i <= 50 THEN ?::timestamptz ELSE ?::timestamptz END, now(), now()"
                + " FROM generate_series(1, 20050) AS s(i)",
                tenantA, tomorrow.toString(), hourAgo.toString(), tomorrow.toString(),
                hourAgo.toString());
        insertRequest(tenantA, "IN_PROGRESS", null, hourAgo);
        detector.detect();
        jdbcTemplate.execute("ANALYZE requests");
        String now = "'" + Instant.now() + "'::timestamptz";

        assertThat(plan("SELECT id FROM requests WHERE status = 'NEW' AND NOT deleted"
                + " AND NOT first_response_overdue AND first_response_due_at <= " + now
                + " ORDER BY first_response_due_at LIMIT 500 FOR UPDATE SKIP LOCKED"))
                .as("выборка просрочки первого ответа")
                .contains("idx_requests_first_response_check").doesNotContain("Seq Scan");
        assertThat(plan("SELECT id FROM requests WHERE status IN ('NEW', 'CONTACTED',"
                + " 'IN_PROGRESS') AND NOT deleted AND NOT resolution_overdue"
                + " AND resolution_due_at <= " + now
                + " ORDER BY resolution_due_at LIMIT 500 FOR UPDATE SKIP LOCKED"))
                .as("выборка просрочки решения")
                .contains("idx_requests_resolution_check").doesNotContain("Seq Scan");
        assertThat(plan("SELECT * FROM requests WHERE tenant_id = '" + tenantA + "'"
                + " AND deleted = false AND (first_response_overdue OR resolution_overdue)"))
                .as("фильтр реестра «просроченные»")
                .contains("idx_requests_tenant_overdue").doesNotContain("Seq Scan");
    }

    /**
     * Миграция на базе, где заявки уже есть: прежний общий признак становится просрочкой
     * решения, просрочка первого ответа начинается с «нет». Миграции прогоняются в отдельной
     * схеме, чтобы не трогать общую базу тестов.
     */
    @Test
    void migration_onDatabaseWithRequests_keepsOldFlagAsResolutionOverdue() {
        String schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
        UUID tenant = UUID.randomUUID();
        UUID overdue = UUID.randomUUID();
        UUID onTime = UUID.randomUUID();
        try {
            migrate(schema, PREVIOUS_MIGRATION);
            jdbcTemplate.update("INSERT INTO " + schema + ".tenants (id, name, slug, active,"
                    + " created_at, updated_at) VALUES (?, 'Был до T-060', ?, true, now(), now())",
                    tenant, slugOf(tenant));
            for (UUID id : List.of(overdue, onTime)) {
                jdbcTemplate.update("INSERT INTO " + schema + ".requests (id, tenant_id, subject,"
                        + " description, status, author_id, overdue, created_at, updated_at)"
                        + " VALUES (?, ?, 'Тема', 'Описание', 'IN_PROGRESS', ?, ?, now(), now())",
                        id, tenant, UUID.randomUUID(), id.equals(overdue));
            }

            migrate(schema, null);

            for (UUID id : List.of(overdue, onTime)) {
                assertThat(jdbcTemplate.queryForMap("SELECT first_response_overdue,"
                        + " resolution_overdue FROM " + schema + ".requests WHERE id = ?", id))
                        .containsEntry("first_response_overdue", false)
                        .containsEntry("resolution_overdue", id.equals(overdue));
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String plan(String sql) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT status, first_response_overdue,"
                + " resolution_overdue, version, updated_at FROM requests WHERE id = ?", id);
    }

    private UUID insertRequest(UUID tenantId, String status, Instant firstResponseDueAt,
            Instant resolutionDueAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, first_response_due_at, resolution_due_at, created_at, updated_at)"
                + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?, ?::timestamptz,"
                + " ?::timestamptz, now(), now())", id, tenantId, status, UUID.randomUUID(),
                firstResponseDueAt == null ? null : firstResponseDueAt.toString(),
                resolutionDueAt == null ? null : resolutionDueAt.toString());
        return id;
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', ?, true, now(), now())", id, slugOf(id));
        return id;
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

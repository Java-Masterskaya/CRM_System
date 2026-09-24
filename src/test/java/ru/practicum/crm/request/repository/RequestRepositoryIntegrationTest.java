package ru.practicum.crm.request.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;

class RequestRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609182038";

    @Autowired
    private RequestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private UUID authorId;

    @BeforeEach
    void createTenants() {
        tenantA = createTenant("Арендатор А");
        tenantB = createTenant("Арендатор Б");
        authorId = UUID.randomUUID();
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION);

        assertThat(success).isTrue();
    }

    @Test
    void save_whenRequestFilled_readsBackEveryField() {
        Request request = newRequest(tenantA, "Выгрузка отчёта");
        UUID typeId = createRequestType(tenantA, "Выгрузка данных");
        UUID assigneeId = UUID.randomUUID();
        request.setTypeId(typeId);
        request.setAssigneeId(assigneeId);
        request.setPriority(RequestPriority.HIGH);
        request.setDataParams(Map.of("format", "csv", "rows", 1000));
        request.setDesiredDueAt(Instant.parse("2026-10-01T09:00:00Z"));
        request.setFirstResponseDueAt(Instant.parse("2026-09-19T12:00:00Z"));
        request.setResolutionDueAt(Instant.parse("2026-09-25T12:00:00Z"));
        request.setOverdue(true);

        UUID id = repository.save(request).getId();
        Request saved = repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getTypeId()).isEqualTo(typeId);
        assertThat(saved.getSubject()).isEqualTo("Выгрузка отчёта");
        assertThat(saved.getDescription()).isEqualTo("Нужен отчёт за квартал");
        assertThat(saved.getDataParams()).containsEntry("format", "csv")
                .containsEntry("rows", 1000);
        assertThat(saved.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(saved.getStatus()).isEqualTo(RequestStatus.NEW);
        assertThat(saved.getAuthorId()).isEqualTo(authorId);
        assertThat(saved.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(saved.getDesiredDueAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(saved.getFirstResponseDueAt())
                .isEqualTo(Instant.parse("2026-09-19T12:00:00Z"));
        assertThat(saved.getResolutionDueAt()).isEqualTo(Instant.parse("2026-09-25T12:00:00Z"));
        assertThat(saved.isOverdue()).isTrue();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_whenTimeStored_keepsItInUtc() {
        Request request = newRequest(tenantA, "Срок в UTC");
        request.setResolutionDueAt(Instant.parse("2026-09-25T12:00:00Z"));

        UUID id = repository.save(request).getId();

        String stored = jdbcTemplate.queryForObject(
                "SELECT to_char(resolution_due_at AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM requests WHERE id = ?",
                String.class, id);

        assertThat(stored).isEqualTo("2026-09-25T12:00:00Z");
    }

    @Test
    void findById_whenRequestBelongsToAnotherTenant_returnsNothing() {
        UUID id = repository.save(newRequest(tenantA, "Заявка арендатора А")).getId();

        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantB)).isEmpty();
        assertThat(repository.existsByIdAndTenantIdAndDeletedFalse(id, tenantB)).isFalse();
        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA)).isPresent();
    }

    @Test
    void findByTenant_whenAnotherTenantHasRequests_returnsOnlyOwn() {
        repository.save(newRequest(tenantA, "Заявка А"));
        repository.save(newRequest(tenantB, "Заявка Б"));

        Page<Request> page = repository.findByTenantIdAndDeletedFalse(tenantA,
                PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Request::getSubject)
                .containsExactly("Заявка А");
    }

    @Test
    void findByStatus_whenStatusesDiffer_returnsOnlyRequestedStatus() {
        repository.save(newRequest(tenantA, "Новая"));
        Request inProgress = newRequest(tenantA, "В работе");
        inProgress.setStatus(RequestStatus.IN_PROGRESS);
        repository.save(inProgress);

        Page<Request> page = repository.findByTenantIdAndStatusAndDeletedFalse(tenantA,
                RequestStatus.IN_PROGRESS, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Request::getSubject).containsExactly("В работе");
    }

    @Test
    void findByTenant_whenRequestMarkedDeleted_isNotReturned() {
        Request request = newRequest(tenantA, "Удалённая");
        request.setDeleted(true);
        UUID id = repository.save(request).getId();

        assertThat(repository.findByTenantIdAndDeletedFalse(tenantA, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA)).isEmpty();

        Integer rowsInTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM requests WHERE id = ?", Integer.class, id);

        assertThat(rowsInTable).isEqualTo(1);
    }

    @Test
    void save_whenRequestChangedAndStoredAgain_raisesVersion() {
        Request request = repository.save(newRequest(tenantA, "Первая версия"));
        long initialVersion = request.getVersion();

        request.setSubject("Вторая версия");
        Request updated = repository.save(request);

        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
    }

    @Test
    void save_whenTwoTransactionsReadSameVersion_secondFailsWithOptimisticLock() throws Exception {

        UUID requestId = transactionTemplate.execute(status ->
                repository.save(newRequest(tenantA, "Исходное значение")).getId());

        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch canSave = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {

            Runnable task = () -> transactionTemplate.execute(status -> {
                Request request = repository
                        .findByIdAndTenantIdAndDeletedFalse(requestId, tenantA)
                        .orElseThrow();

                bothRead.countDown();

                try {
                    canSave.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Поток был прерван", e);
                }

                request.setSubject("Правка от " + Thread.currentThread().getName());
                repository.save(request);

                return null;
            });

            Future<?> first = executor.submit(task);
            Future<?> second = executor.submit(task);

            assertThat(bothRead.await(5, TimeUnit.SECONDS))
                    .as("Оба потока должны прочитать заявку до сохранения")
                    .isTrue();

            canSave.countDown();

            int successes = 0;
            int conflicts = 0;

            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof ObjectOptimisticLockingFailureException) {
                        conflicts++;
                    } else {
                        throw e;
                    }
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }

        Request finalState = repository
                .findByIdAndTenantIdAndDeletedFalse(requestId, tenantA)
                .orElseThrow();

        assertThat(finalState.getVersion()).isEqualTo(1L);
        assertThat(finalState.getSubject())
                .startsWith("Правка от ");
    }

    @Test
    void requireVersion_whenVersionMatches_doesNotThrow() {
        Request saved = repository.save(newRequest(tenantA, "Проверка версии"));

        assertThatCode(() -> saved.requireVersion(saved.getVersion()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireVersion_whenVersionDiffers_throwsOptimisticLock() {
        Request saved = repository.save(newRequest(tenantA, "Проверка версии"));

        assertThat(saved.getVersion()).isEqualTo(0L);

        assertThatThrownBy(() -> saved.requireVersion(1L))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void insertWithUnknownStatus_whenWrittenDirectlyBypassingCode_isRejectedByDatabase() {
        String insertWithBrokenStatus =
                """
                INSERT INTO requests (id, tenant_id, subject, description, status, author_id,
                                      created_at, updated_at)
                VALUES (?, ?, 'тема', 'описание', 'ARCHIVED', ?, now(), now())
                """;

        assertThatThrownBy(() -> jdbcTemplate.update(insertWithBrokenStatus, UUID.randomUUID(),
                tenantA, authorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requests_status_check");
    }

    @Test
    void insertWithoutTenant_whenWrittenDirectlyToDatabase_isRejected() {
        String insertWithoutTenant =
                """
                INSERT INTO requests (
                    id,
                    tenant_id,
                    subject,
                    description,
                    status,
                    author_id,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, NULL, 'тема', 'описание', 'NEW', ?, now(), now()
                )
                """;

        assertThatThrownBy(() -> jdbcTemplate.update(
                insertWithoutTenant,
                UUID.randomUUID(),
                authorId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertWithUnknownTenant_whenWrittenDirectlyToDatabase_isRejected() {
        UUID unknownTenant = UUID.randomUUID();

        String insertWithUnknownTenant =
                """
                INSERT INTO requests (
                    id,
                    tenant_id,
                    subject,
                    description,
                    status,
                    author_id,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, ?, 'тема', 'описание', 'NEW', ?, now(), now()
                )
                """;

        assertThatThrownBy(() -> jdbcTemplate.update(
                insertWithUnknownTenant,
                UUID.randomUUID(),
                unknownTenant,
                authorId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void selectByTenantAndStatus_whenTableIsLarge_usesIndexInsteadOfSequentialScan() {
        String insertManyRequests =
                """
                INSERT INTO requests (id, tenant_id, subject, description, status, author_id,
                                      overdue, deleted, version, created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'тема ' || i, 'описание ' || i,
                       CASE WHEN i = 1 THEN 'ON_HOLD' ELSE 'DONE' END, ?,
                       false, false, 0, now(), now()
                FROM generate_series(1, 5000) AS s(i)
                """;
        jdbcTemplate.update(insertManyRequests, tenantA, authorId);
        jdbcTemplate.execute("ANALYZE requests");

        List<String> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id FROM requests WHERE tenant_id = ? AND status = 'ON_HOLD'",
                String.class, tenantA);

        assertThat(String.join("\n", plan)).contains("idx_requests_tenant_status");
    }

    private UUID createTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, ?, true, now(), now())", id, name);
        return id;
    }

    private UUID createRequestType(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types"
                + " (id, tenant_id, name, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())", id, tenantId, name);
        return id;
    }

    private Request newRequest(UUID tenantId, String subject) {
        return new Request(tenantId, authorId, subject, "Нужен отчёт за квартал",
                RequestStatus.NEW);
    }
}

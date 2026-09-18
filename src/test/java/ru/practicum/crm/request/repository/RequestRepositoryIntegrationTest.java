package ru.practicum.crm.request.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;

class RequestRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609182038";

    @Autowired
    private RequestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        UUID typeId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        request.setTypeId(typeId);
        request.setAssigneeId(assigneeId);
        request.setPriority("HIGH");
        request.setDataParams(Map.of("format", "csv", "rows", 1000));
        request.setDesiredDueAt(Instant.parse("2026-10-01T09:00:00Z"));
        request.setFirstResponseDueAt(Instant.parse("2026-09-19T12:00:00Z"));
        request.setResolutionDueAt(Instant.parse("2026-09-25T12:00:00Z"));
        request.setOverdue(true);

        UUID id = repository.saveAndFlush(request).getId();
        Request saved = repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getTypeId()).isEqualTo(typeId);
        assertThat(saved.getSubject()).isEqualTo("Выгрузка отчёта");
        assertThat(saved.getDescription()).isEqualTo("Нужен отчёт за квартал");
        assertThat(saved.getDataParams()).containsEntry("format", "csv")
                .containsEntry("rows", 1000);
        assertThat(saved.getPriority()).isEqualTo("HIGH");
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

        UUID id = repository.saveAndFlush(request).getId();

        String stored = jdbcTemplate.queryForObject(
                "SELECT to_char(resolution_due_at AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM requests WHERE id = ?",
                String.class, id);

        assertThat(stored).isEqualTo("2026-09-25T12:00:00Z");
    }

    @Test
    void findById_whenRequestBelongsToAnotherTenant_returnsNothing() {
        UUID id = repository.saveAndFlush(newRequest(tenantA, "Заявка арендатора А")).getId();

        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantB)).isEmpty();
        assertThat(repository.existsByIdAndTenantIdAndDeletedFalse(id, tenantB)).isFalse();
        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA)).isPresent();
    }

    @Test
    void findByTenant_whenAnotherTenantHasRequests_returnsOnlyOwn() {
        repository.saveAndFlush(newRequest(tenantA, "Заявка А"));
        repository.saveAndFlush(newRequest(tenantB, "Заявка Б"));

        Page<Request> page = repository.findByTenantIdAndDeletedFalse(tenantA,
                PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Request::getSubject)
                .containsExactly("Заявка А");
    }

    @Test
    void findByStatus_whenStatusesDiffer_returnsOnlyRequestedStatus() {
        repository.saveAndFlush(newRequest(tenantA, "Новая"));
        Request inProgress = newRequest(tenantA, "В работе");
        inProgress.setStatus(RequestStatus.IN_PROGRESS);
        repository.saveAndFlush(inProgress);

        Page<Request> page = repository.findByTenantIdAndStatusAndDeletedFalse(tenantA,
                RequestStatus.IN_PROGRESS, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Request::getSubject).containsExactly("В работе");
    }

    @Test
    void findByTenant_whenRequestMarkedDeleted_isNotReturned() {
        Request request = newRequest(tenantA, "Удалённая");
        request.setDeleted(true);
        UUID id = repository.saveAndFlush(request).getId();

        assertThat(repository.findByTenantIdAndDeletedFalse(tenantA, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(repository.findByIdAndTenantIdAndDeletedFalse(id, tenantA)).isEmpty();
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    void save_whenRequestChangedAndStoredAgain_raisesVersion() {
        Request request = repository.saveAndFlush(newRequest(tenantA, "Первая версия"));
        long initialVersion = request.getVersion();

        request.setSubject("Вторая версия");
        Request updated = repository.saveAndFlush(request);

        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
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

    private Request newRequest(UUID tenantId, String subject) {
        return new Request(tenantId, authorId, subject, "Нужен отчёт за квартал",
                RequestStatus.NEW);
    }
}

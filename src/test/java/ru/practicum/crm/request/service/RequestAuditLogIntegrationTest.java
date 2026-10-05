package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.AuditedField;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestPriority;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Журнал аудита на реальной базе: запись в одной транзакции с изменением, постраничное чтение с
 * арендатором и запрет изменять журнал в самой базе.
 */
class RequestAuditLogIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609271610";
    private static final Instant DUE_AT = Instant.parse("2026-10-01T09:00:00Z");

    @Autowired
    private RequestAuditService audit;

    @Autowired
    private RequestRepository requests;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transaction;
    private UUID tenantA;
    private UUID tenantB;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        tenantA = insertTenant();
        tenantB = insertTenant();
        adminId = UUID.randomUUID();
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    @Test
    void record_whenSignificantFieldsChange_writesEntryPerFieldWithAuthorAndTime() {
        UUID requestId = saveRequest(tenantA);
        UUID typeId = insertRequestType(tenantA);

        transaction.executeWithoutResult(status -> {
            Request request = load(tenantA, requestId);
            final RequestSnapshot before = RequestSnapshot.of(request);
            request.setPriority(RequestPriority.HIGH);
            request.setTypeId(typeId);
            request.setDesiredDueAt(DUE_AT);
            request.setDescription("Нужен отчёт с разбивкой по месяцам");
            audit.record(request, before, adminId);
        });

        List<RequestAuditEntry> journal = journal(tenantA, requestId);
        assertThat(journal).extracting(RequestAuditEntry::getField).containsExactlyInAnyOrder(
                AuditedField.PRIORITY, AuditedField.TYPE, AuditedField.DESIRED_DUE_AT,
                AuditedField.DESCRIPTION);
        assertThat(journal).allSatisfy(entry -> {
            assertThat(entry.getAuthorId()).isEqualTo(adminId);
            assertThat(entry.getCreatedAt()).isNotNull();
        });
        assertThat(journal).filteredOn(entry -> entry.getField() == AuditedField.PRIORITY)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getOldValue()).isEqualTo(RequestPriority.FALLBACK.name());
                    assertThat(entry.getNewValue()).isEqualTo("HIGH");
                });
    }

    @Test
    void record_whenChangeRollsBack_leavesNeitherChangeNorEntry() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Request request = load(tenantA, requestId);
            final RequestSnapshot before = RequestSnapshot.of(request);
            request.setPriority(RequestPriority.HIGH);
            audit.record(request, before, adminId);
            throw new IllegalStateException("Сбой после записи в журнал");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(journal(tenantA, requestId)).isEmpty();
        assertThat(load(tenantA, requestId).getPriority()).isEqualTo(RequestPriority.FALLBACK);
    }

    @Test
    void record_outsideTransaction_isRefused() {
        Request request = load(tenantA, saveRequest(tenantA));

        assertThatThrownBy(() -> audit.record(request, RequestSnapshot.of(request), adminId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void journal_ofAnotherTenantsRequest_isEmpty() {
        UUID requestId = saveRequest(tenantA);
        changePriority(tenantA, requestId);

        assertThat(journal(tenantB, requestId)).isEmpty();
        assertThat(journal(tenantA, requestId)).hasSize(1);
    }

    @Test
    void journal_whenReadPageByPage_returnsEveryEntryOnceInSameOrder() {
        UUID requestId = saveRequest(tenantA);
        UUID typeId = insertRequestType(tenantA);
        transaction.executeWithoutResult(status -> {
            Request request = load(tenantA, requestId);
            final RequestSnapshot before = RequestSnapshot.of(request);
            request.setPriority(RequestPriority.HIGH);
            request.setTypeId(typeId);
            request.setDesiredDueAt(DUE_AT);
            request.setDescription("Нужен отчёт с разбивкой по месяцам");
            audit.record(request, before, adminId);
        });
        changePriority(tenantA, requestId, RequestPriority.LOW);

        Page<RequestAuditEntry> page = audit.journal(tenantA, requestId, PageRequest.of(0, 2));
        List<UUID> pageByPage = new ArrayList<>(ids(page.getContent()));
        while (page.hasNext()) {
            page = audit.journal(tenantA, requestId, page.nextPageable());
            pageByPage.addAll(ids(page.getContent()));
        }

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(pageByPage).doesNotHaveDuplicates()
                .containsExactlyElementsOf(ids(journal(tenantA, requestId)));
    }

    @Test
    void entry_whenChangedOrDeletedDirectlyInDatabase_isRejected() {
        UUID requestId = saveRequest(tenantA);
        changePriority(tenantA, requestId);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE request_audit_log"
                + " SET new_value = 'LOW' WHERE request_id = ?", requestId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM request_audit_log WHERE request_id = ?", requestId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(journal(tenantA, requestId)).singleElement()
                .extracting(RequestAuditEntry::getNewValue).isEqualTo("HIGH");
    }

    /** Журнал заявки целиком — первая страница наибольшего размера; в тестах записей меньше. */
    private List<RequestAuditEntry> journal(UUID tenantId, UUID requestId) {
        return audit.journal(tenantId, requestId, PageRequest.of(0, PageRequests.MAX_SIZE))
                .getContent();
    }

    private static List<UUID> ids(List<RequestAuditEntry> entries) {
        return entries.stream().map(RequestAuditEntry::getId).toList();
    }

    private void changePriority(UUID tenantId, UUID requestId) {
        changePriority(tenantId, requestId, RequestPriority.HIGH);
    }

    private void changePriority(UUID tenantId, UUID requestId, RequestPriority priority) {
        transaction.executeWithoutResult(status -> {
            Request request = load(tenantId, requestId);
            final RequestSnapshot before = RequestSnapshot.of(request);
            request.setPriority(priority);
            audit.record(request, before, adminId);
        });
    }

    private Request load(UUID tenantId, UUID requestId) {
        return requests.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId).orElseThrow();
    }

    private UUID saveRequest(UUID tenantId) {
        return requests.save(new Request(tenantId, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", RequestStatus.NEW)).getId();
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                            + " VALUES (?, 'Арендатор', ?, true, now(), now())",
                id, "test-" + id);
        return id;
    }

    private UUID insertRequestType(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, active, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка', true, now(), now())", id, tenantId);
        return id;
    }
}

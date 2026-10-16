package ru.practicum.crm.request.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.byLessThan;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.RequestAuditService;

class RequestAssignmentServiceImplIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610131400";

    @Autowired
    private RequestAssignmentServiceImpl service;

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
    void changeAssignee_whenCalledInTransaction_persistsAssigneeAndWritesOneAuditEntry() {
        UUID requestId = saveRequest(tenantA);
        UUID assigneeId = UUID.randomUUID();

        RequestAssignmentDto result = transaction.execute(status ->
                service.changeAssignee(tenantA, requestId, assigneeId, 0, adminId));

        assertThat(result.assigneeId()).isEqualTo(assigneeId);
        assertThat(result.assignedAt()).isNotNull();
        Request reloaded = load(tenantA, requestId);
        assertThat(reloaded.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(reloaded.getAssignedAt()).isCloseTo(result.assignedAt(),
                byLessThan(1, ChronoUnit.MILLIS));
        assertThat(journal(tenantA, requestId)).singleElement()
                .satisfies(entry -> assertThat(entry.getAuthorId()).isEqualTo(adminId));
    }

    @Test
    void changeAssignee_outsideTransaction_isRefused() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> service.changeAssignee(tenantA, requestId, UUID.randomUUID(), 0,
                adminId)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void changeAssignee_whenChangeRollsBack_leavesNeitherAssigneeNorAuditEntry() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.changeAssignee(tenantA, requestId, UUID.randomUUID(), 0, adminId);
            throw new IllegalStateException("Сбой после назначения");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(load(tenantA, requestId).getAssigneeId()).isNull();
        assertThat(journal(tenantA, requestId)).isEmpty();
    }

    @Test
    void changeAssignee_forAnotherTenantsRequest_isNotFoundAndNothingChanges() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> transaction.execute(status ->
                service.changeAssignee(tenantB, requestId, UUID.randomUUID(), 0, adminId)))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> assertThat(((NotFoundException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(load(tenantA, requestId).getAssigneeId()).isNull();
    }

    @Test
    void changeAssignee_whenRequestTerminal_isRejectedAsClosedAndNothingChanges() {
        UUID requestId = saveRequest(tenantA, RequestStatus.DONE);

        assertThatThrownBy(() -> transaction.execute(status ->
                service.changeAssignee(tenantA, requestId, UUID.randomUUID(), 0, adminId)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REQUEST_CLOSED));
        assertThat(load(tenantA, requestId).getAssigneeId()).isNull();
    }

    @Test
    void changeAssignee_whenVersionStale_throwsOptimisticLockAndNothingChanges() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> transaction.execute(status ->
                service.changeAssignee(tenantA, requestId, UUID.randomUUID(), 1, adminId)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(load(tenantA, requestId).getAssigneeId()).isNull();
    }

    @Test
    void assignmentCheckConstraint_whenColumnsSetDirectlyOutOfSync_isRejectedByDatabase() {
        UUID requestId = saveRequest(tenantA);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE requests SET assignee_id = ? WHERE id = ?", UUID.randomUUID(), requestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<RequestAuditEntry> journal(UUID tenantId, UUID requestId) {
        return audit.journal(tenantId, requestId, PageRequest.of(0, 20)).getContent();
    }

    private Request load(UUID tenantId, UUID requestId) {
        return requests.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId).orElseThrow();
    }

    private UUID saveRequest(UUID tenantId) {
        return saveRequest(tenantId, RequestStatus.NEW);
    }

    private UUID saveRequest(UUID tenantId, RequestStatus status) {
        return requests.save(new Request(tenantId, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", status)).getId();
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                        + " VALUES (?, 'Арендатор', ?, true, now(), now())",
                id, "test-" + id);
        return id;
    }
}

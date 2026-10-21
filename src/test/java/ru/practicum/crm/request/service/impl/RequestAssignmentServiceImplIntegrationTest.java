package ru.practicum.crm.request.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.byLessThan;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;
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
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(adminId.toString(), null,
                        AuthorityUtils.createAuthorityList("REQUEST_AUDIT_READ")));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
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

    @Test
    void takeUnassigned_whenTwoOperatorsCompete_onlyOneWinsAndAuditIsWritten()
            throws Exception {
        UUID requestId = saveRequest(tenantA);
        UUID operatorA = UUID.randomUUID();
        UUID operatorB = UUID.randomUUID();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Attempt> first = executor.submit(
                    () -> attemptTake(requestId, operatorA, ready, start));
            Future<Attempt> second = executor.submit(
                    () -> attemptTake(requestId, operatorB, ready, start));

            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            assertThat(bothReady)
                    .as("Оба оператора должны быть готовы к старту")
                    .isTrue();

            List<Attempt> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            List<Attempt> successes = results.stream()
                    .filter(result -> result.outcome() != null)
                    .toList();

            List<ErrorCode> errors = results.stream()
                    .map(Attempt::errorCode)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successes).hasSize(1);
            assertThat(successes.getFirst().outcome().changed()).isTrue();
            assertThat(errors)
                    .containsExactly(ErrorCode.REQUEST_ALREADY_ASSIGNED);
        }

        UUID winnerId = load(tenantA, requestId).getAssigneeId();

        assertThat(winnerId).isIn(operatorA, operatorB);

        Request actual = load(tenantA, requestId);

        assertThat(actual.getAssignedAt()).isNotNull();
        assertThat(actual.getVersion()).isEqualTo(1L);

        assertThat(journal(tenantA, requestId))
                .singleElement()
                .satisfies(entry ->
                        assertThat(entry.getAuthorId()).isEqualTo(winnerId));
    }

    @Test
    void takeUnassigned_whenSameOperatorRepeats_doesNotChangeRequestOrAudit() {
        UUID requestId = saveRequest(tenantA);
        UUID operatorId = UUID.randomUUID();

        RequestAssignmentOutcome first = transaction.execute(status ->
                service.takeUnassigned(tenantA, requestId, operatorId));

        Request afterFirst = load(tenantA, requestId);
        long versionBefore = afterFirst.getVersion();
        var assignedAtBefore = afterFirst.getAssignedAt();

        RequestAssignmentOutcome second = transaction.execute(status ->
                service.takeUnassigned(tenantA, requestId, operatorId));

        Request afterSecond = load(tenantA, requestId);

        assertThat(first.changed()).isTrue();
        assertThat(second.changed()).isFalse();

        assertThat(second.assignment().assigneeId()).isEqualTo(operatorId);
        assertThat(second.assignment().assignedAt()).isEqualTo(assignedAtBefore);

        assertThat(afterSecond.getAssigneeId()).isEqualTo(operatorId);
        assertThat(afterSecond.getAssignedAt()).isEqualTo(assignedAtBefore);
        assertThat(afterSecond.getVersion()).isEqualTo(versionBefore);

        assertThat(journal(tenantA, requestId))
                .singleElement()
                .satisfies(entry ->
                        assertThat(entry.getAuthorId()).isEqualTo(operatorId));
    }

    @Test
    void takeUnassigned_whenAssignedToAnotherOperator_isRejectedWithoutChanges() {
        UUID requestId = saveRequest(tenantA);
        UUID existingOperator = UUID.randomUUID();
        UUID anotherOperator = UUID.randomUUID();

        RequestAssignmentDto assigned = transaction.execute(status ->
                service.changeAssignee(
                        tenantA,
                        requestId,
                        existingOperator,
                        0,
                        adminId
                ));

        Request before = load(tenantA, requestId);
        int auditCountBefore = journal(tenantA, requestId).size();

        assertThatThrownBy(() ->
                transaction.execute(status ->
                        service.takeUnassigned(tenantA, requestId, anotherOperator)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex ->
                        assertThat(((ApiException) ex).getErrorCode())
                                .isEqualTo(ErrorCode.REQUEST_ALREADY_ASSIGNED));

        Request after = load(tenantA, requestId);

        assertThat(after.getAssigneeId()).isEqualTo(existingOperator);
        assertThat(after.getAssignedAt())
                .isCloseTo(assigned.assignedAt(), within(1, ChronoUnit.MICROS));
        assertThat(after.getVersion()).isEqualTo(before.getVersion());

        assertThat(journal(tenantA, requestId)).hasSize(auditCountBefore);
    }

    @Test
    void takeUnassigned_whenRequestTerminal_isRejectedAsClosedAndNothingChanges() {
        UUID requestId = saveRequest(tenantA, RequestStatus.DONE);
        long versionBefore = load(tenantA, requestId).getVersion();

        assertThatThrownBy(() ->
                transaction.execute(status ->
                        service.takeUnassigned(
                                tenantA,
                                requestId,
                                UUID.randomUUID()
                        )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex ->
                        assertThat(((ApiException) ex).getErrorCode())
                                .isEqualTo(ErrorCode.REQUEST_CLOSED));

        Request actual = load(tenantA, requestId);

        assertThat(actual.getAssigneeId()).isNull();
        assertThat(actual.getAssignedAt()).isNull();
        assertThat(actual.getVersion()).isEqualTo(versionBefore);
        assertThat(journal(tenantA, requestId)).isEmpty();
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

    private Attempt attemptTake(
            UUID requestId,
            UUID operatorId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        try {
            RequestAssignmentOutcome outcome = transaction.execute(status -> {
                ready.countDown();
                awaitStart(start);
                return service.takeUnassigned(tenantA, requestId, operatorId);
            });

            return new Attempt(outcome, null);
        } catch (ApiException exception) {
            return new Attempt(null, exception.getErrorCode());
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Не получен сигнал начала захвата заявки");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поток прерван во время ожидания", exception);
        }
    }

    private record Attempt(
            RequestAssignmentOutcome outcome,
            ErrorCode errorCode
    ) {
    }
}

package ru.practicum.crm.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.testsupport.AuthTestFixture;
import ru.practicum.testsupport.AuthTestFixture.TestTenant;
import ru.practicum.testsupport.AuthTestFixture.TestUser;

@Import(AuthTestFixture.class)
class AssignmentServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AssignmentService service;

    @Autowired
    private AuthTestFixture fixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void assign_whenCandidateIsOperatorOfSameTenant_persistsAssigneeAndPublishesNotification() {
        TestTenant tenant = fixture.createTenant("tenant-assign-ok");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser operator = fixture.createUser(tenant, "operator@example.com",
                "StrongPassword1!");
        fixture.grantPermission(tenant, operator, "REQUEST_STATUS_CHANGE");
        UUID requestId = saveRequest(tenant.id(), author.id());

        RequestAssignmentDto result = transaction.execute(status ->
                service.assign(tenant.id(), requestId, operator.id(), 0, UUID.randomUUID()));

        assertThat(result.assigneeId()).isEqualTo(operator.id());
        assertThat(assigneeIdOf(requestId)).isEqualTo(operator.id());

        Map<String, Object> event = singleOutboxEvent(tenant.id(), requestId);
        assertThat(event.get("event_type")).isEqualTo("EMAIL_NOTIFICATION");
        JsonNode payload = readPayload(event);
        assertThat(payload.path("recipientEmail").asText()).isEqualTo("author@example.com");
        assertThat(payload.path("notificationType").asText()).isEqualTo("REQUEST_ASSIGNED");
        assertThat(payload.path("variables").path("assigneeName").asText())
                .isEqualTo("operator@example.com");
    }

    @Test
    void assign_whenCandidateBelongsToAnotherTenant_isNotFoundAndNothingChanges() {
        TestTenant ownTenant = fixture.createTenant("tenant-own");
        TestTenant otherTenant = fixture.createTenant("tenant-other");
        TestUser author = fixture.createUser(ownTenant, "author@example.com",
                "StrongPassword1!");
        TestUser foreignOperator = fixture.createUser(otherTenant, "operator@example.com",
                "StrongPassword1!");
        fixture.grantPermission(otherTenant, foreignOperator, "REQUEST_STATUS_CHANGE");
        UUID requestId = saveRequest(ownTenant.id(), author.id());

        assertThatThrownBy(() -> transaction.execute(status ->
                service.assign(ownTenant.id(), requestId, foreignOperator.id(), 0,
                        UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> assertThat(((NotFoundException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(assigneeIdOf(requestId)).isNull();
        assertThat(outboxEvents(ownTenant.id(), requestId)).isEmpty();
    }

    @Test
    void assign_whenCandidateLacksRightToLeadRequest_isForbiddenAndNothingChanges() {
        TestTenant tenant = fixture.createTenant("tenant-no-permission");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser bystander = fixture.createUser(tenant, "bystander@example.com",
                "StrongPassword1!");
        UUID requestId = saveRequest(tenant.id(), author.id());

        assertThatThrownBy(() -> transaction.execute(status ->
                service.assign(tenant.id(), requestId, bystander.id(), 0, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        assertThat(assigneeIdOf(requestId)).isNull();
        assertThat(outboxEvents(tenant.id(), requestId)).isEmpty();
    }

    @Test
    void assign_whenUnassigning_doesNotPublishNotification() {
        TestTenant tenant = fixture.createTenant("tenant-unassign");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser operator = fixture.createUser(tenant, "operator@example.com",
                "StrongPassword1!");
        fixture.grantPermission(tenant, operator, "REQUEST_STATUS_CHANGE");
        UUID requestId = saveRequest(tenant.id(), author.id());
        transaction.execute(status ->
                service.assign(tenant.id(), requestId, operator.id(), 0, UUID.randomUUID()));

        RequestAssignmentDto result = transaction.execute(status ->
                service.assign(tenant.id(), requestId, null, 1, UUID.randomUUID()));

        assertThat(result.assigneeId()).isNull();
        assertThat(outboxEvents(tenant.id(), requestId)).hasSize(1);
    }

    @Test
    void autoAssign_whenLoadsDiffer_assignsOperatorWithFewestUnfinishedRequests() {
        TestTenant tenant = fixture.createTenant("tenant-auto-least-loaded");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser loaded = operator(tenant, "loaded@example.com");
        TestUser free = operator(tenant, "free@example.com");
        saveRequest(tenant.id(), author.id(), loaded.id(), "NEW");
        saveRequest(tenant.id(), author.id(), loaded.id(), "IN_PROGRESS");
        UUID requestId = saveRequest(tenant.id(), author.id());

        RequestAssignmentDto result = transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID()));

        assertThat(result.assigneeId()).isEqualTo(free.id());
        assertThat(assigneeIdOf(requestId)).isEqualTo(free.id());
    }

    @Test
    void autoAssign_whenFinishedRequestsLoaded_doesNotCountThemAsWorkload() {
        TestTenant tenant = fixture.createTenant("tenant-auto-finished");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser withFinishedOnly = operator(tenant, "finished@example.com");
        TestUser withOneOpen = operator(tenant, "open@example.com");
        saveRequest(tenant.id(), author.id(), withFinishedOnly.id(), "DONE");
        saveRequest(tenant.id(), author.id(), withFinishedOnly.id(), "CANCELLED");
        saveRequest(tenant.id(), author.id(), withOneOpen.id(), "NEW");
        UUID requestId = saveRequest(tenant.id(), author.id());

        RequestAssignmentDto result = transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID()));

        assertThat(result.assigneeId()).isEqualTo(withFinishedOnly.id());
    }

    @Test
    void autoAssign_whenOperatorIsBlockedOrDeleted_neverPicksThem() {
        TestTenant tenant = fixture.createTenant("tenant-auto-inactive");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser blocked = operator(tenant, "blocked@example.com");
        TestUser deleted = operator(tenant, "deleted@example.com");
        TestUser active = operator(tenant, "active@example.com");
        fixture.blockUser(tenant.id(), blocked.id());
        fixture.deleteUser(tenant.id(), deleted.id());
        saveRequest(tenant.id(), author.id(), active.id(), "NEW");
        saveRequest(tenant.id(), author.id(), active.id(), "NEW");
        UUID requestId = saveRequest(tenant.id(), author.id());

        RequestAssignmentDto result = transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID()));

        assertThat(result.assigneeId()).isEqualTo(active.id());
    }

    @Test
    void autoAssign_whenOnlyBlockedOperatorHasRight_returnsExplainedConflictWithoutAssignee() {
        TestTenant tenant = fixture.createTenant("tenant-auto-no-candidates");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        TestUser blocked = operator(tenant, "blocked@example.com");
        fixture.blockUser(tenant.id(), blocked.id());
        UUID requestId = saveRequest(tenant.id(), author.id());

        assertThatThrownBy(() -> transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NO_ELIGIBLE_ASSIGNEE));

        assertThat(assigneeIdOf(requestId)).isNull();
        assertThat(auditEntries(requestId)).isEmpty();
        assertThat(outboxEvents(tenant.id(), requestId)).isEmpty();
    }

    @Test
    void autoAssign_whenTenantHasNoOperators_returnsExplainedConflictKeepingRequestOpen() {
        TestTenant tenant = fixture.createTenant("tenant-auto-no-operators");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        UUID requestId = saveRequest(tenant.id(), author.id());

        assertThatThrownBy(() -> transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NO_ELIGIBLE_ASSIGNEE));

        assertThat(assigneeIdOf(requestId)).isNull();
    }

    @Test
    void autoAssign_whenAnotherTenantHasFreeOperator_keepsSelectionInsideTenant() {
        TestTenant ownTenant = fixture.createTenant("tenant-auto-own");
        TestTenant otherTenant = fixture.createTenant("tenant-auto-other");
        TestUser author = fixture.createUser(ownTenant, "author@example.com",
                "StrongPassword1!");
        TestUser ownOperator = operator(ownTenant, "own@example.com");
        operator(otherTenant, "other@example.com");
        saveRequest(ownTenant.id(), author.id(), ownOperator.id(), "NEW");
        saveRequest(ownTenant.id(), author.id(), ownOperator.id(), "NEW");
        UUID requestId = saveRequest(ownTenant.id(), author.id());

        RequestAssignmentDto result = transaction.execute(status ->
                service.autoAssign(ownTenant.id(), requestId, 0, UUID.randomUUID()));

        assertThat(result.assigneeId()).isEqualTo(ownOperator.id());
    }

    @Test
    void autoAssign_whenSucceeded_writesAssigneeChangeToAuditLogLikeManualAssignment() {
        TestTenant tenant = fixture.createTenant("tenant-auto-audit");
        TestUser author = fixture.createUser(tenant, "author@example.com", "StrongPassword1!");
        final TestUser operator = operator(tenant, "operator@example.com");
        UUID requestId = saveRequest(tenant.id(), author.id());
        UUID actorId = UUID.randomUUID();

        transaction.execute(status ->
                service.autoAssign(tenant.id(), requestId, 0, actorId));

        List<Map<String, Object>> audit = auditEntries(requestId);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("field")).isEqualTo("ASSIGNEE");
        assertThat(audit.get(0).get("old_value")).isNull();
        assertThat(audit.get(0).get("new_value")).isEqualTo(operator.id().toString());
        assertThat(audit.get(0).get("author_id")).isEqualTo(actorId);
        assertThat(outboxEvents(tenant.id(), requestId)).hasSize(1);
    }

    @Test
    void autoAssign_whenTenRequestsOnThreeOperators_keepsSpreadWithinOneRequest() {
        TestTenant tenant = fixture.createTenant("tenant-auto-balance");
        final TestUser author = fixture.createUser(tenant, "author@example.com",
                "StrongPassword1!");
        operator(tenant, "operator-a@example.com");
        operator(tenant, "operator-b@example.com");
        operator(tenant, "operator-c@example.com");
        List<UUID> requests = List.of(
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()),
                saveRequest(tenant.id(), author.id()));

        for (UUID requestId : requests) {
            transaction.execute(status ->
                    service.autoAssign(tenant.id(), requestId, 0, UUID.randomUUID()));
        }

        LongSummaryStatistics load = loadByAssignee(tenant.id()).values().stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        assertThat(load.getCount()).isEqualTo(3);
        assertThat(load.getSum()).isEqualTo(10);
        assertThat(load.getMax() - load.getMin()).isLessThanOrEqualTo(1);
    }

    private TestUser operator(TestTenant tenant, String email) {
        TestUser operator = fixture.createUser(tenant, email, "StrongPassword1!");
        fixture.grantPermission(tenant, operator, "REQUEST_STATUS_CHANGE");
        return operator;
    }

    private UUID saveRequest(UUID tenantId, UUID authorId) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO requests (id, tenant_id, author_id, subject, description, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'Выгрузка за квартал', 'Нужен отчёт', 'NEW',"
                        + " now(), now())",
                requestId, tenantId, authorId);
        return requestId;
    }

    private UUID saveRequest(UUID tenantId, UUID authorId, UUID assigneeId, String status) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO requests (id, tenant_id, author_id, subject, description, status,"
                        + " assignee_id, assigned_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?,"
                        + " now(), now(), now())",
                requestId, tenantId, authorId, status, assigneeId);
        return requestId;
    }

    private Map<UUID, Long> loadByAssignee(UUID tenantId) {
        Map<UUID, Long> load = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT assignee_id, count(*) AS active FROM requests"
                        + " WHERE tenant_id = ? AND NOT deleted AND assignee_id IS NOT NULL"
                        + " GROUP BY assignee_id",
                tenantId)) {
            load.put((UUID) row.get("assignee_id"), ((Number) row.get("active")).longValue());
        }
        return load;
    }

    private List<Map<String, Object>> auditEntries(UUID requestId) {
        return jdbcTemplate.queryForList(
                "SELECT field, old_value, new_value, author_id FROM request_audit_log"
                        + " WHERE request_id = ?",
                requestId);
    }

    private UUID assigneeIdOf(UUID requestId) {
        return jdbcTemplate.queryForObject("SELECT assignee_id FROM requests WHERE id = ?",
                UUID.class, requestId);
    }

    private Map<String, Object> singleOutboxEvent(UUID tenantId, UUID requestId) {
        List<Map<String, Object>> events = outboxEvents(tenantId, requestId);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private List<Map<String, Object>> outboxEvents(UUID tenantId, UUID requestId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type, payload FROM outbox_events"
                        + " WHERE tenant_id = ? AND aggregate_type = 'REQUEST'"
                        + " AND aggregate_id = ?",
                tenantId, requestId);
    }

    private JsonNode readPayload(Map<String, Object> event) {
        try {
            return objectMapper.readTree(event.get("payload").toString());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

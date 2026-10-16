package ru.practicum.crm.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

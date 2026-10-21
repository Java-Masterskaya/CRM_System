package ru.practicum.crm.request.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class RequestCardIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTestFixture authTestFixture;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RequestAuditService audit;
    @Autowired private RequestRepository requests;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestTenant other;
    private String operatorToken;
    private String clientToken;
    private UUID clientId;
    private UUID requestId;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        transaction = new TransactionTemplate(transactionManager);
        tenant = insertTenant();
        other = insertTenant();
        insertUser(tenant, "operator@example.com", "OPERATOR");
        operatorToken = login(tenant, "operator@example.com");
        clientId = insertUser(tenant, "client@example.com", "CLIENT");
        clientToken = login(tenant, "client@example.com");
        now = Instant.now();
        requestId = insertRequest(tenant, UUID.randomUUID(), "IN_PROGRESS", "HIGH", daysAgo(1));
    }

    @Test
    void card_forOperator_returnsAllFields() throws Exception {
        mockMvc.perform(get("/admin/requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.subject").value("Выгрузка за квартал"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.firstResponseOverdue").value(false))
                .andExpect(jsonPath("$.resolutionOverdue").value(false))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "ADMIN"})
    void card_foreignTenantRequest_isNotFound(String role) throws Exception {
        String email = role.toLowerCase(Locale.ROOT) + "2@example.com";
        insertUser(tenant, email, role);
        String token = login(tenant, email);
        UUID foreign = insertRequest(other, UUID.randomUUID(), "NEW", "LOW", daysAgo(1));

        mockMvc.perform(get("/admin/requests/{id}", foreign)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void card_forClientOnOwnRequest_isForbidden() throws Exception {
        UUID ownRequest = insertRequest(tenant, clientId, "NEW", "LOW", daysAgo(1));

        mockMvc.perform(get("/admin/requests/{id}", ownRequest)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusHistory_returnsChronologicalOrder() throws Exception {
        UUID newRequest = insertRequest(tenant, UUID.randomUUID(), "NEW", "NORMAL", daysAgo(1));
        changeStatus(newRequest, "CONTACTED", 0);
        changeStatus(newRequest, "IN_PROGRESS", 1);
        changeStatus(newRequest, "ON_HOLD", 2);

        mockMvc.perform(get("/admin/requests/{id}/status-history", newRequest)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fromStatus").value("NEW"))
                .andExpect(jsonPath("$.content[0].toStatus").value("CONTACTED"))
                .andExpect(jsonPath("$.content[1].toStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.content[2].toStatus").value("ON_HOLD"));
    }

    @Test
    void statusHistory_foreignTenantRequest_isNotFound() throws Exception {
        UUID foreign = insertRequest(other, UUID.randomUUID(), "NEW", "LOW", daysAgo(1));

        mockMvc.perform(get("/admin/requests/{id}/status-history", foreign)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditLog_returnsChronologicalOrder() throws Exception {
        recordChange(requestId, request -> request.setPriority(RequestPriority.URGENT));
        recordChange(requestId, request -> request.setDescription("Уточнённое описание"));

        mockMvc.perform(get("/admin/requests/{id}/audit-log", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].field").value("PRIORITY"))
                .andExpect(jsonPath("$.content[1].field").value("DESCRIPTION"));
    }

    @Test
    void auditLog_forClient_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/requests/{id}/audit-log", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLog_foreignTenantRequest_isNotFound() throws Exception {
        UUID foreign = insertRequest(other, UUID.randomUUID(), "NEW", "LOW", daysAgo(1));

        mockMvc.perform(get("/admin/requests/{id}/audit-log", foreign)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isNotFound());
    }

    private void recordChange(UUID targetRequestId, Consumer<Request> edit) {
        transaction.executeWithoutResult(status -> {
            Request request = requests
                    .findByIdAndTenantIdAndDeletedFalse(targetRequestId, tenant.id())
                    .orElseThrow();
            final RequestSnapshot before = RequestSnapshot.of(request);
            edit.accept(request);
            audit.record(request, before, UUID.randomUUID());
        });
    }

    private void changeStatus(UUID targetRequestId, String targetStatus, long version)
            throws Exception {
        mockMvc.perform(put("/admin/requests/{id}/status", targetRequestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", targetStatus, "version", version))))
                .andExpect(status().isOk());
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    private UUID insertRequest(AuthTestFixture.TestTenant tenantOfRequest, UUID authorId,
            String requestStatus, String priority, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " priority, priority_rank, author_id, created_at, updated_at)"
                + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', ?, ?, ?, ?,"
                + " ?::timestamptz, now())", id, tenantOfRequest.id(), requestStatus, priority,
                RequestPriority.valueOf(priority).getRank(), authorId, createdAt.toString());
        return id;
    }

    private String login(AuthTestFixture.TestTenant tenantOfUser, String email) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenantOfUser.slug(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private AuthTestFixture.TestTenant insertTenant() {
        AuthTestFixture.TestTenant created = authTestFixture.createTenant(
                "card-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, created.id());
        return created;
    }

    private UUID insertUser(AuthTestFixture.TestTenant tenantOfUser, String email,
            String roleCode) {
        UUID id = authTestFixture.createUser(tenantOfUser, email, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenantOfUser.id(), roleCode);
        return id;
    }
}

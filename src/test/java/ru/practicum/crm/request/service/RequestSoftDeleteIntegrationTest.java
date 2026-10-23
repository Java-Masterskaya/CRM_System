package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
public class RequestSoftDeleteIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "password";
    private static final String ADMIN = "admin@example.com";
    private static final String OPERATOR = "operator@example.com";
    private static final String CLIENT = "client@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private RequestAccess requestAccess;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RequestSoftDeleteService softDeleteService;

    @Autowired
    private TenantContext tenantContext;

    private AuthTestFixture.TestTenant tenant;
    private String adminToken;
    private String operatorToken;
    private UUID authorId;
    private UUID requestId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        authorId = insertUser(tenant, CLIENT, "CLIENT");
        insertUser(tenant, ADMIN, "ADMIN");
        insertUser(tenant, OPERATOR, "OPERATOR");
        adminToken = login(ADMIN);
        operatorToken = login(OPERATOR);
        requestId = insertRequest(authorId);
        insertStatusHistory(requestId);
        insertAuditEntry(requestId);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
    }

    /** DoD: Удалённая заявка удаляется из реестре, списка клиентов и обеих карточках (404).*/
    @Test
    void delete_hidesFromRegistryClientListAndBothCards() throws Exception {
        mockMvc.perform(delete("/admin/requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        tenantContext.setTenantId(tenant.id());
        assertThat(requestRepository.findByTenantIdAndAuthorIdAndDeletedFalse(
                tenant.id(), authorId, PageRequest.of(0, 10))).isEmpty();

        singIn(authorId, "REQUEST_READ_OWN");
        assertThatThrownBy(() -> requestAccess.readable(tenant.id(), requestId))
                .isInstanceOf(NotFoundException.class);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deleted, deleted_at, status FROM requests WHERE id = ?", requestId
        );

        assertThat(row.get("deleted")).isEqualTo(true);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(historyCount(requestId)).isEqualTo(1);
        assertThat(auditCount(requestId)).isEqualTo(1);
    }

    /** DoD: Восстановление возвращает заявку в реестр и список автора в прежнем статусе.*/
    @Test
    void restore_returnsToRegistryAndAuthorListWithSameStatus() throws Exception {

        mockMvc.perform(delete("/admin/requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/admin/requests/{id}/restore", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(get("/admin/requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(requestId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("IN_PROGRESS"));

        tenantContext.setTenantId(tenant.id());
        List<Request> ownList = requestRepository.findByTenantIdAndAuthorIdAndDeletedFalse(
                tenant.id(), authorId, PageRequest.of(0, 10)
        ).getContent();

        assertThat(ownList).extracting(Request::getId).containsExactly(requestId);
        assertThat(ownList.getFirst().getStatus().name()).isEqualTo("IN_PROGRESS");

        singIn(authorId, "REQUEST_READ_OWN");
        assertThat(requestAccess.readable(tenant.id(), requestId).getId()).isEqualTo(requestId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deleted, deleted_at FROM requests WHERE id = ?", requestId
        );
        assertThat(row.get("deleted")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNull();
        assertThat(historyCount(requestId)).isEqualTo(1);
        assertThat(auditCount(requestId)).isEqualTo(1);
    }

    /** DoD: Оператор без прав администратора получает 403 при удалении и восстановлении.*/
    @Test
    void operator_withoutDeletedPermission_isForbidden() throws Exception {
        mockMvc.perform(delete("/admin/requests/{id}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("UPDATE requests SET deleted = TRUE, deleted_at = NOW()"
                + " WHERE id = ?", requestId);

        mockMvc.perform(post("/admin/requests/{id}/restore", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM requests WHERE id = ?", Boolean.class, requestId
        )).isTrue();
    }

    @Test
    void service_operatorPermission_isDeniedBeforeMutation() {
        singIn(UUID.randomUUID(), "REQUEST_READ_ALL", "REQUEST_STATUS_CHANGE");
        tenantContext.setTenantId(tenant.id());

        assertThatThrownBy(() -> softDeleteService.delete(requestId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> softDeleteService.restore(requestId))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void singIn(UUID authorId, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(
                        authorId.toString(), null, AuthorityUtils.createAuthorityList(permissions)
                )
        );
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenant.slug(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private AuthTestFixture.TestTenant insertTenant() {
        AuthTestFixture.TestTenant created = authTestFixture.createTenant(
                "soft-del-" + UUID.randomUUID().toString().replace("-", "")
        );

        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, created.id());
        return created;
    }

    private UUID insertUser(AuthTestFixture.TestTenant tenantOfUser, String email,
                            String roleCode) {
        UUID id = authTestFixture.createUser(tenantOfUser, email, PASSWORD).id();
        jdbcTemplate.update(
                "INSERT INTO user_roles (tenant_id, user_id, role_id)"
                        + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenantOfUser.id(), roleCode
        );
        return id;
    }

    private UUID insertRequest(UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, deleted, version, created_at, updated_at)"
                + " VALUES (?, ?, 'Тема', 'Описание', 'IN_PROGRESS', ?, false, 0, now(), now())",
                id, tenant.id(), authorId);
        return id;
    }

    private void insertStatusHistory(UUID requestId) {
        jdbcTemplate.update("INSERT INTO request_status_history (id, tenant_id, request_id,"
                        + " from_status, to_status, author_id, comment, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'NEW', 'IN_PROGRESS', ?, NULL, now(), now())",
                UUID.randomUUID(), tenant.id(), requestId, authorId);
    }

    private void insertAuditEntry(UUID requestId) {
        jdbcTemplate.update("INSERT INTO request_audit_log (id, tenant_id, request_id, field,"
                        + " old_value, new_value, author_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'subject', 'Было', 'Стало', ?, now(), now())",
                UUID.randomUUID(), tenant.id(), requestId, authorId);
    }

    private int historyCount(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_status_history WHERE request_id = ?",
                Integer.class, id
        );
    }

    private int auditCount(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_audit_log WHERE request_id = ?",
                Integer.class, id
        );
    }
}

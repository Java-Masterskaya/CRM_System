package ru.practicum.crm.user.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.provisioning.AdminProvisioning;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
@Import(AuthTestFixture.class)
class AdminUserControllerIntegrationTest extends BaseIntegrationTest {

    private static final String USERS = "/admin/users";
    private static final String SLUG = "admin-users-http";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private AdminProvisioning adminProvisioning;

    @Autowired
    private AuthTestFixture fixture;

    private AuthTestFixture.TestTenant tenant;
    private UUID adminId;
    private String adminToken;

    @BeforeEach
    void createAdmin() throws Exception {
        tenantContext.clear();
        tenantService.createTenant(new TenantDto("Администраторский API", SLUG, true));
        tenant = new AuthTestFixture.TestTenant(tenantService.findActiveBySlug(SLUG).id(), SLUG);
        adminId = adminProvisioning.createInitialAdmin(tenant.id(), "admin@example.com", PASSWORD);
        adminToken = login(SLUG, "admin@example.com").path("accessToken").asText();
    }

    @AfterEach
    void clearTenant() {
        tenantContext.clear();
    }

    @Test
    void givenAdmin_whenCreatingOperator_thenReturns201AndOperatorCanReadRegistry()
            throws Exception {
        String created = call(post(USERS).header("Idempotency-Key", "operator")
                        .content(json(createRequest("operator@example.com"))), adminToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("operator@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(created).doesNotContain(PASSWORD);

        String operator = login(SLUG, "operator@example.com").path("accessToken").asText();
        call(get("/admin/requests"), operator).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "ADMIN,OPERATOR"})
    void givenAdmin_whenCreatingAnotherAdmin_thenNewUserCanManageTenantUsers(String roles)
            throws Exception {
        AdminUserCreateRequest request = new AdminUserCreateRequest("new-admin@example.com",
                "Новый администратор", PASSWORD, List.of(roles.split(",")));
        call(post(USERS).header("Idempotency-Key", "new-admin").content(json(request)), adminToken)
                .andExpect(status().isCreated());

        String token = login(SLUG, "new-admin@example.com").path("accessToken").asText();
        call(get(USERS), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        call(post(USERS).header("Idempotency-Key", "created-by-new-admin")
                        .content(json(createRequest("operator@example.com"))), token)
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenTwoDevices_whenBlockingOrDemoting_thenRejectsBothSessionsButKeepsOthers(
            boolean demote) throws Exception {
        AuthTestFixture.TestUser target = account("target@example.com", "ADMIN");
        final List<JsonNode> devices = List.of(login(SLUG, "target@example.com"),
                login(SLUG, "target@example.com"));
        account("unaffected@example.com", "OPERATOR");
        final JsonNode unaffected = login(SLUG, "unaffected@example.com");
        MockHttpServletRequestBuilder update = demote
                ? put(USERS + "/" + target.id() + "/roles")
                        .content(json(Map.of("roles", List.of("OPERATOR"))))
                : patch(USERS + "/" + target.id() + "/status")
                        .content(json(Map.of("status", "BLOCKED")));
        call(update.header("Idempotency-Key", "revoke-devices"), adminToken)
                .andExpect(status().isOk());

        for (JsonNode device : devices) {
            assertProblem(call(get("/client/profile"), device.path("accessToken").asText()),
                    401, "UNAUTHENTICATED");
            assertRefreshDenied(device);
        }
        call(get(USERS), adminToken).andExpect(status().isOk());
        call(get("/client/profile"), unaffected.path("accessToken").asText())
                .andExpect(status().isOk());
        call(post("/auth/refresh").content(json(Map.of(
                        "refreshToken", unaffected.path("refreshToken").asText()))), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void givenInvalidUpdate_whenChangingUser_thenKeepsAccountRolesAndTokens(
            String operation, String body) throws Exception {
        final AuthTestFixture.TestUser target = account("target@example.com", "OPERATOR");
        final JsonNode tokens = login(SLUG, "target@example.com");
        MockHttpServletRequestBuilder update = "roles".equals(operation)
                ? put(USERS + "/" + target.id() + "/roles")
                : patch(USERS + "/" + target.id() + "/status");
        assertProblem(call(update.header("Idempotency-Key", "invalid-update")
                .content(body), adminToken), 400, "VALIDATION_FAILED");

        call(get(USERS).param("role", "OPERATOR"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(target.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].roles.length()").value(1));
        call(get("/client/profile"), tokens.path("accessToken").asText())
                .andExpect(status().isOk());
        call(post("/auth/refresh").content(json(Map.of(
                        "refreshToken", tokens.path("refreshToken").asText()))), null)
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE tenant_id = ?",
                Long.class, tenant.id())).isZero();
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
                Arguments.of("roles", "{}"),
                Arguments.of("roles", "{\"roles\":[]}"),
                Arguments.of("roles", "{\"roles\":[null]}"),
                Arguments.of("roles", "{\"roles\":[\" \"]}"),
                Arguments.of("roles", "{\"roles\":[\"OPERATOR\",\"OPERATOR\"]}"),
                Arguments.of("roles", "{\"roles\":[\"UNKNOWN\"]}"),
                Arguments.of("status", "{}"),
                Arguments.of("status", "{\"status\":null}"),
                Arguments.of("status", "{\"status\":\"UNKNOWN\"}"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenKeyUsedForAnotherUser_whenUpdating_thenRejectsAndKeepsSecondUser(
            boolean roles) throws Exception {
        AuthTestFixture.TestUser first = account("first@example.com", "OPERATOR");
        final AuthTestFixture.TestUser second = account("second@example.com", "OPERATOR");
        for (AuthTestFixture.TestUser target : List.of(first, second)) {
            MockHttpServletRequestBuilder update = roles
                    ? put(USERS + "/" + target.id() + "/roles")
                            .content(json(Map.of("roles", List.of("CLIENT"))))
                    : patch(USERS + "/" + target.id() + "/status")
                            .content(json(Map.of("status", "BLOCKED")));
            ResultActions result = call(update.header("Idempotency-Key", "same-key"), adminToken);
            if (target.id().equals(first.id())) {
                result.andExpect(status().isOk());
            } else {
                assertProblem(result, 409, "IDEMPOTENCY_KEY_REUSED");
            }
        }
        call(get(USERS).param("role", "OPERATOR").param("status", "ACTIVE"), adminToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second.id().toString()));
    }

    @Test
    void givenUnblockedUser_whenReplayingBlock_thenReturnsSnapshotAndKeepsNewSession()
            throws Exception {
        AuthTestFixture.TestUser target = account("target@example.com", "OPERATOR");
        final String path = USERS + "/" + target.id() + "/status";
        final String blockBody = json(Map.of("status", "BLOCKED"));
        final String snapshot = call(patch(path).header("Idempotency-Key", "original-block")
                        .content(blockBody), adminToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        call(patch(path).header("Idempotency-Key", "unblock")
                        .content(json(Map.of("status", "ACTIVE"))), adminToken)
                .andExpect(status().isOk());
        final JsonNode tokens = login(SLUG, "target@example.com");

        call(patch(path).header("Idempotency-Key", "original-block").content(blockBody), adminToken)
                .andExpect(status().isOk()).andExpect(content().json(snapshot));
        call(get(USERS).param("status", "ACTIVE").param("role", "OPERATOR"), adminToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/client/profile"), tokens.path("accessToken").asText())
                .andExpect(status().isOk());
        call(post("/auth/refresh").content(json(Map.of(
                        "refreshToken", tokens.path("refreshToken").asText()))), null)
                .andExpect(status().isOk());
    }

    @Test
    void givenSeveralUsers_whenFilteringSortingAndPaging_thenUsesExactSortExpressions()
            throws Exception {
        AuthTestFixture.TestUser first = account("a@example.com", "OPERATOR");
        AuthTestFixture.TestUser second = account("z@example.com", "OPERATOR");
        assignRole(tenant, first, "ADMIN");
        call(patch(USERS + "/" + second.id() + "/status")
                        .header("Idempotency-Key", "block-z")
                        .content(json(Map.of("status", "BLOCKED"))), adminToken)
                .andExpect(status().isOk());

        call(get(USERS).param("role", "OPERATOR").param("status", "ACTIVE")
                        .param("sort", "email,desc"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$.content[0].roles[0]").value("ADMIN"));
        call(get(USERS).param("role", "OPERATOR").param("size", "1")
                        .param("sort", "name,asc", "email,desc"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("z@example.com"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        call(get(USERS).param("role", "OPERATOR").param("size", "1").param("page", "1")
                        .param("sort", "name,asc", "email,desc"), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("a@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANONYMOUS", "CLIENT", "OPERATOR", "READER"})
    void givenMissingPermissions_whenCallingUserOperations_thenReturns401Or403(String role)
            throws Exception {
        String token = null;
        if (!"ANONYMOUS".equals(role)) {
            account("limited@example.com", role);
            token = login(SLUG, "limited@example.com").path("accessToken").asText();
        }
        int denial = token == null ? 401 : 403;
        String code = token == null ? "UNAUTHENTICATED" : "ACCESS_DENIED";
        if ("READER".equals(role)) {
            call(get(USERS), token).andExpect(status().isOk());
        } else {
            assertProblem(call(get(USERS), token), denial, code);
        }
        assertProblem(call(post(USERS).header("Idempotency-Key", "denied-create")
                .content(json(createRequest("forbidden@example.com"))), token), denial, code);
        assertProblem(call(patch(USERS + "/" + adminId + "/status")
                .header("Idempotency-Key", "denied-status")
                .content(json(Map.of("status", "BLOCKED"))), token), denial, code);
        assertProblem(call(put(USERS + "/" + adminId + "/roles")
                .header("Idempotency-Key", "denied-roles")
                .content(json(Map.of("roles", List.of("CLIENT")))), token), denial, code);
    }

    @ParameterizedTest
    @MethodSource("invalidListParameters")
    void givenInvalidListParameter_whenListing_thenReturnsProblemDetails400(
            String name, String value)
            throws Exception {
        assertProblem(call(get(USERS).param(name, value), adminToken), 400, "VALIDATION_FAILED");
    }

    static Stream<Arguments> invalidListParameters() {
        return Stream.of(Arguments.of("page", "-1"), Arguments.of("size", "101"),
                Arguments.of("status", "UNKNOWN"), Arguments.of("role", " "),
                Arguments.of("sort", "passwordHash,asc"), Arguments.of("sort", "email,sideways"));
    }

    @Test
    void givenMissingKeyOrWeakPassword_whenCreating_thenReportsProblemFields() throws Exception {
        call(post(USERS).content(json(createRequest("operator@example.com"))), adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.parameter=='Idempotency-Key')]").isNotEmpty());
        AdminUserCreateRequest weak = new AdminUserCreateRequest("operator@example.com", "Оператор",
                "weak", List.of("OPERATOR"));
        call(post(USERS).header("Idempotency-Key", "weak").content(json(weak)), adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.pointer=='#/temporaryPassword')]").isNotEmpty());
    }

    @Test
    void givenRepeatedCreate_whenReplaying_thenPreserves201AndDetectsConflicts() throws Exception {
        String body = json(createRequest("operator@example.com"));
        String first = call(post(USERS).header("Idempotency-Key", "same").content(body), adminToken)
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        call(post(USERS).header("Idempotency-Key", "same").content(body), adminToken)
                .andExpect(status().isCreated()).andExpect(content().json(first));
        assertProblem(call(post(USERS).header("Idempotency-Key", "same")
                .content(json(createRequest("another@example.com"))), adminToken),
                409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(call(post(USERS).header("Idempotency-Key", "duplicate")
                .content(json(createRequest("OPERATOR@example.com"))), adminToken),
                409, "ALREADY_EXISTS");
    }

    @Test
    void givenForeignUser_whenListingOrUpdating_thenHidesUserAndReturns404() throws Exception {
        tenantService.createTenant(new TenantDto("Другой арендатор", "admin-users-other", true));
        AuthTestFixture.TestTenant other = new AuthTestFixture.TestTenant(
                tenantService.findActiveBySlug("admin-users-other").id(), "admin-users-other");
        final AuthTestFixture.TestUser foreign =
                fixture.createUser(other, "foreign@example.com", PASSWORD);
        assignRole(other, foreign, "OPERATOR");

        call(get(USERS), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id")
                        .value(not(hasItem(foreign.id().toString()))));
        assertProblem(call(patch(USERS + "/" + foreign.id() + "/status")
                .header("Idempotency-Key", "foreign-status")
                .content(json(Map.of("status", "BLOCKED"))), adminToken), 404, "NOT_FOUND");
        assertProblem(call(put(USERS + "/" + foreign.id() + "/roles")
                .header("Idempotency-Key", "foreign-roles")
                .content(json(Map.of("roles", List.of("CLIENT")))), adminToken), 404, "NOT_FOUND");
    }

    @Test
    void givenTenantIdInBody_whenCreating_thenUsesOnlyAuthenticatedTenant() throws Exception {
        UUID suppliedTenant = UUID.randomUUID();
        JsonNode created = objectMapper.readTree(call(post(USERS)
                        .header("Idempotency-Key", "tenant-in-body")
                        .content(json(Map.of("email", "operator@example.com", "name", "Оператор",
                                "temporaryPassword", PASSWORD, "roles", List.of("OPERATOR"),
                                "tenantId", suppliedTenant))), adminToken)
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        assertThat(jdbcTemplate.queryForObject("SELECT tenant_id FROM users WHERE id = ?",
                UUID.class, UUID.fromString(created.path("id").asText()))).isEqualTo(tenant.id());
    }

    @Test
    void givenLiveOperator_whenBlockingAndUnblocking_thenOldTokensStayRejected() throws Exception {
        AuthTestFixture.TestUser operator = account("operator@example.com", "OPERATOR");
        final JsonNode tokens = login(SLUG, "operator@example.com");
        String path = USERS + "/" + operator.id() + "/status";
        call(get("/client/profile"), tokens.path("accessToken").asText())
                .andExpect(status().isOk());

        call(patch(path).header("Idempotency-Key", "block")
                        .content(json(Map.of("status", "BLOCKED"))), adminToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        assertProblem(call(get("/client/profile"), tokens.path("accessToken").asText()),
                401, "UNAUTHENTICATED");
        assertRefreshDenied(tokens);
        call(patch(path).header("Idempotency-Key", "unblock")
                        .content(json(Map.of("status", "ACTIVE"))), adminToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertProblem(call(get("/client/profile"), tokens.path("accessToken").asText()),
                401, "UNAUTHENTICATED");
        String fresh = login(SLUG, "operator@example.com").path("accessToken").asText();
        call(get("/client/profile"), fresh)
                .andExpect(status().isOk());
    }

    @Test
    void givenLiveAdmin_whenRemovingAdminRole_thenRequiresNewLoginAndDeniesUserManagement()
            throws Exception {
        AuthTestFixture.TestUser managed = account("managed@example.com", "ADMIN");
        final JsonNode tokens = login(SLUG, "managed@example.com");

        call(put(USERS + "/" + managed.id() + "/roles").header("Idempotency-Key", "demote")
                        .content(json(Map.of("roles", List.of("OPERATOR")))), adminToken)
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
        assertProblem(call(get(USERS), tokens.path("accessToken").asText()),
                401, "UNAUTHENTICATED");
        assertRefreshDenied(tokens);
        String fresh = login(SLUG, "managed@example.com").path("accessToken").asText();
        assertProblem(call(get(USERS), fresh), 403, "ACCESS_DENIED");
        call(get("/client/profile"), fresh).andExpect(status().isOk());
    }

    @Test
    void givenOwnAdminAccount_whenBlockingOrDemoting_thenReturns409AndKeepsAccess()
            throws Exception {
        assertProblem(call(patch(USERS + "/" + adminId + "/status")
                .header("Idempotency-Key", "self-block")
                .content(json(Map.of("status", "BLOCKED"))), adminToken),
                409, "USER_SELF_BLOCK_FORBIDDEN");
        assertProblem(call(put(USERS + "/" + adminId + "/roles")
                .header("Idempotency-Key", "self-demote")
                .content(json(Map.of("roles", List.of("CLIENT")))), adminToken),
                409, "USER_SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN");
        call(get(USERS), adminToken).andExpect(status().isOk());
    }

    @Test
    void givenAdmin_whenCallingUnlistedUserRoute_thenKeepsDefaultDeny() throws Exception {
        assertProblem(call(get(USERS + "/" + adminId + "/roles"), adminToken),
                403, "ACCESS_DENIED");
        assertProblem(call(post(USERS + "/unexpected"), adminToken), 403, "ACCESS_DENIED");
    }

    @Test
    void givenRegisteredController_whenReadingOpenApi_thenDocumentsRoutesAndRepeatedSort()
            throws Exception {
        call(get("/v3/api-docs"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/admin/users'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/admin/users/{id}/status'].patch").exists())
                .andExpect(jsonPath("$.paths['/admin/users/{id}/roles'].put").exists())
                .andExpect(jsonPath("$.paths['/admin/users'].get.parameters[?(@.name=='sort')]"
                        + ".schema.type").value(hasItem("array")));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token)
            throws Exception {
        tenantContext.clear();
        request.with(anonymous()).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private JsonNode login(String slug, String email) throws Exception {
        return objectMapper.readTree(call(post("/auth/login").content(json(Map.of(
                        "tenantSlug", slug, "email", email, "password", PASSWORD))), null)
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private AuthTestFixture.TestUser account(String email, String role) {
        AuthTestFixture.TestUser user = fixture.createUser(tenant, email, PASSWORD);
        jdbcTemplate.update("UPDATE users SET name = 'Одинаковое имя' WHERE id = ?", user.id());
        if ("READER".equals(role)) {
            fixture.grantPermission(tenant, user, "USER_READ");
        } else {
            assignRole(tenant, user, role);
        }
        return user;
    }

    private void assignRole(AuthTestFixture.TestTenant accountTenant, AuthTestFixture.TestUser user,
            String role) {
        int inserted = jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                        + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?"
                        + " ON CONFLICT DO NOTHING", user.id(), accountTenant.id(), role);
        assertThat(inserted).isEqualTo(1);
    }

    private AdminUserCreateRequest createRequest(String email) {
        return new AdminUserCreateRequest(email, "Новый оператор", PASSWORD, List.of("OPERATOR"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void assertRefreshDenied(JsonNode tokens) throws Exception {
        assertProblem(call(post("/auth/refresh").content(json(Map.of(
                "refreshToken", tokens.path("refreshToken").asText()))), null),
                401, "UNAUTHENTICATED");
    }

    private void assertProblem(ResultActions result, int expected, String code) throws Exception {
        result.andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        String requestId = result.andReturn().getResponse().getHeader("X-Request-Id");
        result.andExpect(jsonPath("$.requestId").value(requestId));
    }
}

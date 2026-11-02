package ru.practicum.crm.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleCode;
import ru.practicum.crm.user.repository.UserRoleRepository;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
class AdminUserCreationServiceIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String EMAIL = "operator@example.com";

    @Autowired
    private AdminUserCreationService service;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID actorId;
    private UUID otherTenantId;
    private UUID otherActorId;

    @BeforeEach
    void createActors() {
        tenantContext.clear();
        tenantId = createTenant("creation-a");
        actorId = createActor(tenantId);
        otherTenantId = createTenant("creation-b");
        otherActorId = createActor(otherTenantId);
        authenticate(tenantId, actorId, "USER_MANAGE");
    }

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        TestSecurityContextHolder.clearContext();
    }

    @Test
    void givenCreatedOperator_whenLoggingIn_thenCanReadTenantRequestRegistry() throws Exception {
        IdempotencyResult<AdminUserResponse> result = service.create(request("OPERATOR"), "create");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().status()).isEqualTo(UserAccountStatus.ACTIVE);
        UserEntity saved = userRepository.findById(result.body().id(), tenantId).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();

        String response = mockMvc.perform(post("/auth/login").with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("tenantSlug", "creation-a", "email", EMAIL,
                                        "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(response);
        mockMvc.perform(get("/admin/requests")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + tokens.path("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void givenBothRoles_whenCreating_thenAssignsTenantRolesAndKeepsSecretsOutOfResponse()
            throws Exception {
        AdminUserResponse created = service.create(request("ADMIN", "OPERATOR"), "both").body();

        assertThat(created.roles()).containsExactly("ADMIN", "OPERATOR");
        assertThat(userRoleRepository.findRoleCodesByUserIds(tenantId, List.of(created.id())))
                .extracting(UserRoleCode::code).containsExactlyInAnyOrder("ADMIN", "OPERATOR");
        final String json = objectMapper.writeValueAsString(created);
        String stored = jdbcTemplate.queryForObject(
                "SELECT response_body FROM idempotency_records WHERE tenant_id = ?",
                String.class, tenantId);
        assertThat(json).doesNotContain(PASSWORD, "password", "passwordHash", "temporaryPassword");
        assertThat(stored).isEqualTo(json);
    }

    @Test
    void givenDuplicateEmailWithDifferentCase_whenCreating_thenRejectsWithoutExtraRecords() {
        service.create(request("OPERATOR"), "first");
        AdminUserCreateRequest duplicate = new AdminUserCreateRequest(
                " OPERATOR@example.com ", "Другой пользователь", PASSWORD, List.of("ADMIN"));

        assertError(() -> service.create(duplicate, "second"), ErrorCode.ALREADY_EXISTS);

        assertThat(countUsers(tenantId)).isEqualTo(1);
        assertThat(countIdempotencyRecords()).isEqualTo(1);
    }

    @Test
    void givenSameEmailInOtherTenant_whenCreating_thenCreatesIndependentUser() {
        final UUID first = service.create(request("OPERATOR"), "same-key").body().id();
        authenticate(otherTenantId, otherActorId, "USER_MANAGE");

        UUID second = service.create(request("OPERATOR"), "same-key").body().id();

        assertThat(second).isNotEqualTo(first);
        assertThat(countUsers(tenantId)).isEqualTo(1);
        assertThat(countUsers(otherTenantId)).isEqualTo(1);
    }

    @Test
    void givenReorderedRolesWithSameKey_whenRepeating_thenReturnsOriginalResult() {
        final IdempotencyResult<AdminUserResponse> first = service.create(
                request("ADMIN", "OPERATOR"), "repeat");

        IdempotencyResult<AdminUserResponse> repeated = service.create(
                request("OPERATOR", "ADMIN"), "repeat");

        assertThat(repeated).isEqualTo(first);
        assertThat(countUsers(tenantId)).isEqualTo(1);
    }

    @Test
    void givenChangedPasswordWithSameKey_whenRepeating_thenRejectsDifferentRequest() {
        service.create(request("OPERATOR"), "repeat");
        AdminUserCreateRequest changed = new AdminUserCreateRequest(EMAIL, "Оператор",
                "AnotherPassword2!", List.of("OPERATOR"));

        assertError(() -> service.create(changed, "repeat"), ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(countUsers(tenantId)).isEqualTo(1);
    }

    @Test
    void givenWeakPassword_whenCreating_thenReportsTemporaryPasswordFieldAndRollsBack() {
        AdminUserCreateRequest weak = new AdminUserCreateRequest(EMAIL, "Оператор", "weak",
                List.of("OPERATOR"));

        assertThatThrownBy(() -> service.create(weak, "weak"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getErrors()).extracting(ValidationError::pointer)
                            .containsOnly("#/temporaryPassword");
                });
        assertThat(countUsers(tenantId)).isZero();
        assertThat(countIdempotencyRecords()).isZero();
    }

    @Test
    void givenInvalidFieldsOrClientRole_whenCreating_thenRejectsDirectServiceCall() {
        AdminUserCreateRequest invalid = new AdminUserCreateRequest("invalid-email", " ", PASSWORD,
                List.of("CLIENT"));

        assertError(() -> service.create(invalid, "invalid"), ErrorCode.VALIDATION_FAILED);
        assertThat(countUsers(tenantId)).isZero();
    }

    @Test
    void givenRoleOnlyInAnotherTenant_whenCreating_thenRejectsWithoutPartialUser() {
        UUID localRole = roleRepository.findByTenantIdAndCode(tenantId, "OPERATOR")
                .orElseThrow().getId();
        jdbcTemplate.update("DELETE FROM role_permissions WHERE tenant_id = ? AND role_id = ?",
                tenantId, localRole);
        jdbcTemplate.update("DELETE FROM roles WHERE tenant_id = ? AND id = ?",
                tenantId, localRole);

        assertError(() -> service.create(request("OPERATOR"), "missing-role"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(countUsers(tenantId)).isZero();
        assertThat(countIdempotencyRecords()).isZero();
    }

    @Test
    void givenOuterTransactionRollback_whenCreating_thenLeavesNoUserRolesOrSnapshot() {
        transactions.executeWithoutResult(status -> {
            service.create(request("ADMIN", "OPERATOR"), "rollback");
            status.setRollbackOnly();
        });

        assertThat(countUsers(tenantId)).isZero();
        assertThat(countIdempotencyRecords()).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT user_id FROM user_roles WHERE tenant_id = ? AND user_id <> ?",
                UUID.class, tenantId, actorId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenConcurrentRequests_whenCreatingSameEmail_thenCreatesOnlyOneUser(boolean sameKey)
            throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final Future<String> first = executor.submit(
                    () -> concurrentCreate("first", ready, start));
            final Future<String> second = executor.submit(() -> concurrentCreate(
                    sameKey ? "first" : "second", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));
            assertThat(countUsers(tenantId)).isEqualTo(1);
            if (sameKey) {
                assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
                assertThat(outcomes).doesNotContain(ErrorCode.ALREADY_EXISTS.name());
            } else {
                assertThat(outcomes).containsOnlyOnce(ErrorCode.ALREADY_EXISTS.name());
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" "})
    void givenMissingIdempotencyKey_whenCreating_thenRejectsRequest(String key) {
        assertError(() -> service.create(request("OPERATOR"), key), ErrorCode.VALIDATION_FAILED);
        assertThat(countUsers(tenantId)).isZero();
    }

    @Test
    void givenNoManagePermission_whenCreating_thenDeniesAccess() {
        authenticate(tenantId, actorId, "USER_READ");

        assertThatThrownBy(() -> service.create(request("OPERATOR"), "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(countUsers(tenantId)).isZero();
    }

    private String concurrentCreate(String key, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        authenticate(tenantId, actorId, "USER_MANAGE");
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                return service.create(request("OPERATOR"), key).body().id().toString();
            } catch (ApiException exception) {
                if (exception.getErrorCode() != ErrorCode.ALREADY_EXISTS) {
                    throw exception;
                }
                return exception.getErrorCode().name();
            }
        } finally {
            clearContexts();
        }
    }

    private void authenticate(UUID targetTenantId, UUID userId, String... permissions) {
        tenantContext.setTenantId(targetTenantId);
        TestSecurityContextHolder.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null,
                        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private UUID createTenant(String slug) {
        tenantService.createTenant(new TenantDto("Арендатор " + slug, slug, true));
        return tenantService.findActiveBySlug(slug).id();
    }

    private UUID createActor(UUID targetTenantId) {
        tenantContext.setTenantId(targetTenantId);
        try {
            UserEntity user = userRepository.save(new UserEntity(targetTenantId,
                    "admin@example.com", "fixture-hash", UserStatus.ACTIVE));
            RoleEntity role = roleRepository.findByTenantIdAndCode(targetTenantId, "ADMIN")
                    .orElseThrow();
            userRoleRepository.save(new UserRoleEntity(
                    new UserRoleId(user.getId(), role.getId()), targetTenantId));
            return user.getId();
        } finally {
            tenantContext.clear();
        }
    }

    private AdminUserCreateRequest request(String... roles) {
        return new AdminUserCreateRequest(EMAIL, "Оператор", PASSWORD, List.of(roles));
    }

    private int countUsers(UUID targetTenantId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE tenant_id = ? AND lower(email) = lower(?)",
                UUID.class, targetTenantId, EMAIL).size();
    }

    private int countIdempotencyRecords() {
        return jdbcTemplate.queryForList("SELECT id FROM idempotency_records WHERE tenant_id = ?",
                UUID.class, tenantId).size();
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}

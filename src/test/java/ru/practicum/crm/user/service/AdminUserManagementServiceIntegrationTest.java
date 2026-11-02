package ru.practicum.crm.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.AdminUserRolesUpdateRequest;
import ru.practicum.crm.user.api.dto.AdminUserStatusUpdateRequest;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleRepository;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
class AdminUserManagementServiceIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private AdminUserManagementService service;

    @Autowired
    private AdminUserCreationService creationService;

    @Autowired
    private AdminUserQueryService queryService;

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
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    private UUID tenantId;
    private UUID actorId;
    private ManagedUser target;

    @BeforeEach
    void createUsers() {
        tenantContext.clear();
        tenantService.createTenant(new TenantDto("Управление пользователями", "manage-a", true));
        tenantId = tenantService.findActiveBySlug("manage-a").id();
        actorId = createActor(tenantId);
        authenticate("USER_MANAGE", "USER_READ");
        AdminUserResponse user = creationService.create(new AdminUserCreateRequest(
                "managed@example.com", "Пользователь", PASSWORD, List.of("ADMIN", "OPERATOR")),
                "setup").body();
        target = new ManagedUser(user.id(), user.email(), "manage-a");
    }

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        TestSecurityContextHolder.clearContext();
    }

    @Test
    void givenSeveralLogins_whenBlockingThenUnblocking_thenRevokesEveryOldRefresh()
            throws Exception {
        final String first = login(target);
        final String second = login(target);

        assertThat(changeStatus(UserAccountStatus.BLOCKED, "block").body().status())
                .isEqualTo(UserAccountStatus.BLOCKED);
        assertRefreshStatus(first, 401);
        assertRefreshStatus(second, 401);
        assertThat(changeStatus(UserAccountStatus.ACTIVE, "unblock").body().status())
                .isEqualTo(UserAccountStatus.ACTIVE);
        assertRefreshStatus(first, 401);
        assertRefreshStatus(login(target), 200);
    }

    @Test
    void givenAdminWithSeveralLogins_whenRemovingAdmin_thenRevokesEveryRefresh() throws Exception {
        final String first = login(target);
        final String second = login(target);

        AdminUserResponse changed = service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("CLIENT", "OPERATOR")), "demote").body();

        assertThat(changed.roles()).containsExactly("CLIENT", "OPERATOR");
        assertThat(currentUser(target.id()).roles()).containsExactly("CLIENT", "OPERATOR");
        assertRefreshStatus(first, 401);
        assertRefreshStatus(second, 401);
        assertRefreshStatus(login(target), 200);
    }

    @Test
    void givenRetainedAdminRole_whenChangingOtherRoles_thenKeepsRefreshUsable() throws Exception {
        final String refresh = login(target);

        service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("ADMIN", "CLIENT")), "keep-admin");

        assertThat(currentUser(target.id()).roles()).containsExactly("ADMIN", "CLIENT");
        assertRefreshStatus(refresh, 200);
    }

    @Test
    void givenReorderedRolesWithSameKey_whenRepeating_thenReturnsSameResult() {
        final IdempotencyResult<AdminUserResponse> first = service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("ADMIN", "CLIENT")), "roles");

        assertThat(service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("CLIENT", "ADMIN")), "roles"))
                .isEqualTo(first);
        assertError(() -> service.updateRoles(actorId,
                new AdminUserRolesUpdateRequest(List.of("CLIENT", "ADMIN")), "roles"),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void givenUnblockedUser_whenReplayingOldBlock_thenDoesNotBlockOrRevokeAgain() throws Exception {
        final IdempotencyResult<AdminUserResponse> blocked = changeStatus(
                UserAccountStatus.BLOCKED, "block");
        changeStatus(UserAccountStatus.ACTIVE, "unblock");
        final String refresh = login(target);

        assertThat(changeStatus(UserAccountStatus.BLOCKED, "block")).isEqualTo(blocked);
        assertThat(currentUser(target.id()).status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertRefreshStatus(refresh, 200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenRolledBackTransaction_whenUpdating_thenRestoresUserRolesAndRefresh(boolean roles)
            throws Exception {
        final String refresh = login(target);
        transactions.executeWithoutResult(status -> {
            if (roles) {
                service.updateRoles(target.id(),
                        new AdminUserRolesUpdateRequest(List.of("OPERATOR")), "rollback");
            } else {
                changeStatus(UserAccountStatus.BLOCKED, "rollback");
            }
            status.setRollbackOnly();
        });

        assertThat(currentUser(target.id()).status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(currentUser(target.id()).roles()).containsExactly("ADMIN", "OPERATOR");
        assertRefreshStatus(refresh, 200);
    }

    @Test
    void givenForeignUnknownAndDeletedUsers_whenUpdating_thenReturnsSameNotFound() {
        tenantContext.clear();
        tenantService.createTenant(new TenantDto("Другой арендатор", "manage-b", true));
        UUID otherTenant = tenantService.findActiveBySlug("manage-b").id();
        tenantContext.setTenantId(otherTenant);
        final UserEntity foreign = userRepository.save(new UserEntity(otherTenant,
                "foreign@example.com", "fixture-hash", UserStatus.ACTIVE));
        authenticate("USER_MANAGE", "USER_READ");
        UserEntity deleted = userRepository.findById(target.id(), tenantId).orElseThrow();
        deleted.delete();
        userRepository.save(deleted);

        for (UUID id : List.of(foreign.getId(), target.id(), UUID.randomUUID())) {
            assertError(() -> service.updateStatus(id,
                    new AdminUserStatusUpdateRequest(UserAccountStatus.BLOCKED), id.toString()),
                    ErrorCode.NOT_FOUND);
            assertError(() -> service.updateRoles(id,
                    new AdminUserRolesUpdateRequest(List.of("CLIENT")), id.toString()),
                    ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void givenRoleOnlyInAnotherTenant_whenUpdating_thenKeepsOriginalRolesAndRefresh()
            throws Exception {
        final String refresh = login(target);
        tenantContext.clear();
        tenantService.createTenant(new TenantDto("Другие роли", "manage-b", true));
        UUID otherTenant = tenantService.findActiveBySlug("manage-b").id();
        tenantContext.setTenantId(otherTenant);
        roleRepository.save(new RoleEntity(otherTenant, "FOREIGN", "Чужая роль", null));
        authenticate("USER_MANAGE", "USER_READ");

        assertError(() -> service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("FOREIGN")), "foreign-role"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(currentUser(target.id()).roles()).containsExactly("ADMIN", "OPERATOR");
        assertRefreshStatus(refresh, 200);
    }

    @Test
    void givenOwnAccount_whenBlockingOrRemovingAdmin_thenRejectsBothOperations() {
        assertError(() -> service.updateStatus(actorId,
                new AdminUserStatusUpdateRequest(UserAccountStatus.BLOCKED), "self-block"),
                ErrorCode.USER_SELF_BLOCK_FORBIDDEN);
        assertError(() -> service.updateRoles(actorId,
                new AdminUserRolesUpdateRequest(List.of("CLIENT")), "self-demote"),
                ErrorCode.USER_SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN);

        assertThat(currentUser(actorId).status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(currentUser(actorId).roles()).containsExactly("ADMIN");
        service.updateRoles(actorId, new AdminUserRolesUpdateRequest(List.of("ADMIN", "CLIENT")),
                "self-keep-admin");
        assertThat(currentUser(actorId).roles()).containsExactly("ADMIN", "CLIENT");
    }

    @Test
    void givenOwnAccountWithOtherRoles_whenRemovingOtherRoles_thenKeepsAdmin() {
        service.updateRoles(actorId,
                new AdminUserRolesUpdateRequest(List.of("ADMIN", "OPERATOR", "CLIENT")), "expand");

        service.updateRoles(actorId, new AdminUserRolesUpdateRequest(List.of("ADMIN")), "reduce");

        assertThat(currentUser(actorId).roles()).containsExactly("ADMIN");
        assertThat(currentUser(actorId).status()).isEqualTo(UserAccountStatus.ACTIVE);
    }

    @Test
    void givenRejectedSelfChanges_whenCheckingSideEffects_thenKeepsRefreshAndNoSnapshot()
            throws Exception {
        UserEntity actor = userRepository.findById(actorId, tenantId).orElseThrow();
        actor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userRepository.save(actor);
        final String refresh = login(new ManagedUser(actorId, "actor@example.com", "manage-a"));

        assertError(() -> service.updateStatus(actorId,
                new AdminUserStatusUpdateRequest(UserAccountStatus.BLOCKED), "rejected-self-block"),
                ErrorCode.USER_SELF_BLOCK_FORBIDDEN);
        assertError(() -> service.updateRoles(actorId,
                new AdminUserRolesUpdateRequest(List.of("CLIENT")), "rejected-self-demote"),
                ErrorCode.USER_SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN);

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM idempotency_records WHERE tenant_id = ? AND user_id = ?"
                        + " AND idempotency_key IN ('rejected-self-block', 'rejected-self-demote')",
                UUID.class, tenantId, actorId)).isEmpty();
        assertThat(currentUser(actorId).roles()).containsExactly("ADMIN");
        assertThat(currentUser(actorId).status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertRefreshStatus(refresh, 200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenConcurrentSelfChanges_whenOneIsForbidden_thenKeepsActiveAdmin(boolean block)
            throws Exception {
        List<String> outcomes = runConcurrent(() -> {
            try {
                if (block) {
                    service.updateStatus(actorId,
                            new AdminUserStatusUpdateRequest(UserAccountStatus.BLOCKED), "deny");
                } else {
                    service.updateRoles(actorId,
                            new AdminUserRolesUpdateRequest(List.of("CLIENT")), "deny");
                }
                return "OK";
            } catch (ApiException exception) {
                return exception.getErrorCode().name();
            }
        }, () -> {
            service.updateRoles(actorId,
                    new AdminUserRolesUpdateRequest(List.of("ADMIN", "CLIENT")), "allow");
            return "OK";
        });

        ErrorCode expected = block ? ErrorCode.USER_SELF_BLOCK_FORBIDDEN
                : ErrorCode.USER_SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN;
        assertThat(outcomes).containsExactlyInAnyOrder("OK", expected.name());
        assertThat(currentUser(actorId).roles()).containsExactly("ADMIN", "CLIENT");
        assertThat(currentUser(actorId).status()).isEqualTo(UserAccountStatus.ACTIVE);
    }

    @Test
    void givenConcurrentAllowedSelfChanges_whenReplacingRoles_thenSerializesWholeSets()
            throws Exception {
        List<String> outcomes = runConcurrent(() -> {
            service.updateRoles(actorId,
                    new AdminUserRolesUpdateRequest(List.of("ADMIN", "CLIENT")), "client");
            return "OK";
        }, () -> {
            service.updateRoles(actorId,
                    new AdminUserRolesUpdateRequest(List.of("ADMIN", "OPERATOR")), "operator");
            return "OK";
        });

        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(currentUser(actorId).roles()).isIn(
                List.of("ADMIN", "CLIENT"), List.of("ADMIN", "OPERATOR"));
        assertThat(currentUser(actorId).status()).isEqualTo(UserAccountStatus.ACTIVE);
    }

    @Test
    void givenNoManagePermission_whenUpdating_thenDeniesBothOperations() {
        authenticate("REQUEST_READ_ALL");

        assertThatThrownBy(() -> changeStatus(UserAccountStatus.BLOCKED, "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("CLIENT")), "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void givenInvalidUpdates_whenUpdating_thenRejectsWithoutChanges() {
        assertError(() -> service.updateStatus(target.id(),
                new AdminUserStatusUpdateRequest(null), "invalid"), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of()), "invalid"),
                ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("CLIENT", "CLIENT")), "invalid"),
                ErrorCode.VALIDATION_FAILED);
        assertError(() -> changeStatus(UserAccountStatus.BLOCKED, " "),
                ErrorCode.VALIDATION_FAILED);
        assertThat(currentUser(target.id()).status()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(currentUser(target.id()).roles()).containsExactly("ADMIN", "OPERATOR");
    }

    @Test
    void givenLiveAccess_whenBlockingAndUnblocking_thenOldTokenNeverWorksAgain() throws Exception {
        final String access = loginTokens(target).path("accessToken").asText();
        assertAccessStatus(access, 200);
        assertThat(jwtDecoder.decode(access).getExpiresAt()).isAfter(Instant.now());

        changeStatus(UserAccountStatus.BLOCKED, "block-access");
        assertAccessStatus(access, 401);
        changeStatus(UserAccountStatus.ACTIVE, "unblock-access");
        assertAccessStatus(access, 401);

        String fresh = loginTokens(target).path("accessToken").asText();
        assertAccessStatus(fresh, 200);
        Number version = jwtDecoder.decode(fresh).getClaim("access_version");
        assertThat(Objects.requireNonNull(version).longValue()).isEqualTo(1);
    }

    @Test
    void givenLiveAdminAccess_whenDemoting_thenRejectsOldAndIssuesOnlyNewPermissions()
            throws Exception {
        final String access = loginTokens(target).path("accessToken").asText();
        assertAccessStatus(access, 200);
        service.updateRoles(target.id(),
                new AdminUserRolesUpdateRequest(List.of("OPERATOR")), "demote-access");

        assertAccessStatus(access, 401);
        String fresh = loginTokens(target).path("accessToken").asText();
        assertAccessStatus(fresh, 200);
        assertThat(jwtDecoder.decode(fresh).getClaimAsStringList("permissions"))
                .contains("REQUEST_READ_ALL").doesNotContain("USER_MANAGE", "ROLE_MANAGE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenRolledBackRevocation_whenPresentingOldAccess_thenStillAcceptsToken(boolean roles)
            throws Exception {
        final String access = loginTokens(target).path("accessToken").asText();
        transactions.executeWithoutResult(status -> {
            if (roles) {
                service.updateRoles(target.id(),
                        new AdminUserRolesUpdateRequest(List.of("OPERATOR")), "rollback-access");
            } else {
                changeStatus(UserAccountStatus.BLOCKED, "rollback-access");
            }
            status.setRollbackOnly();
        });

        assertAccessStatus(access, 200);
    }

    @Test
    void givenLegacyAccessWithoutVersion_whenRevoking_thenPreservesUpgradeButRejectsOldAccess()
            throws Exception {
        String original = loginTokens(target).path("accessToken").asText();
        final String legacy = signedAccess(original, null);
        assertAccessStatus(legacy, 200);

        changeStatus(UserAccountStatus.BLOCKED, "legacy-block");
        changeStatus(UserAccountStatus.ACTIVE, "legacy-unblock");

        assertAccessStatus(legacy, 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "text", "fraction", "unknown"})
    void givenInvalidAccessVersion_whenAuthenticating_thenReturnsUnauthorized(String kind)
            throws Exception {
        String original = loginTokens(target).path("accessToken").asText();
        Object version = switch (kind) {
            case "negative" -> -1L;
            case "text" -> "invalid";
            case "fraction" -> 0.5;
            default -> 1L;
        };

        assertAccessStatus(signedAccess(original, version), 401);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenConcurrentRevocation_whenRefreshWaits_thenRejectsOldRefresh(boolean roles)
            throws Exception {
        String refresh = login(target);

        int response = authWhileUpdating(roles, "/auth/refresh", Map.of("refreshToken", refresh));

        assertThat(response).isEqualTo(401);
        assertRefreshStatus(refresh, 401);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenConcurrentRevocation_whenLoginWaits_thenUsesCommittedUserState(boolean roles)
            throws Exception {
        int response = authWhileUpdating(roles, "/auth/login", Map.of("tenantSlug", target.slug(),
                "email", target.email(), "password", PASSWORD));

        assertThat(response).isEqualTo(roles ? 200 : 401);
        if (roles) {
            String fresh = loginTokens(target).path("accessToken").asText();
            assertThat(jwtDecoder.decode(fresh).getClaimAsStringList("permissions"))
                    .doesNotContain("USER_MANAGE");
            assertAccessStatus(fresh, 200);
        }
    }

    private int authWhileUpdating(boolean roles, String path, Map<String, String> body)
            throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CompletableFuture<Integer> backend = new CompletableFuture<>();
        try {
            Future<Integer> pending = Objects.requireNonNull(transactions.execute(status -> {
                if (roles) {
                    service.updateRoles(target.id(),
                            new AdminUserRolesUpdateRequest(List.of("OPERATOR")), "racing-revoke");
                } else {
                    changeStatus(UserAccountStatus.BLOCKED, "racing-revoke");
                }
                Future<Integer> request = executor.submit(() -> {
                    clearContexts();
                    try {
                        return Objects.requireNonNull(transactions.execute(tx -> {
                            backend.complete(Objects.requireNonNull(jdbcTemplate.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class)));
                            try {
                                return mockMvc.perform(post(path).with(anonymous())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(body)))
                                        .andReturn().getResponse().getStatus();
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            } finally {
                                // Внешняя транзакция нужна только для наблюдения за блокировкой.
                                tx.setRollbackOnly();
                            }
                        }));
                    } finally {
                        clearContexts();
                    }
                });
                int pid = Objects.requireNonNull(backend.orTimeout(10, TimeUnit.SECONDS).join());
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                        .until(() -> !jdbcTemplate.queryForList(
                                "SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted",
                                Integer.class, pid).isEmpty());
                return request;
            }));
            return Objects.requireNonNull(pending.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String signedAccess(String original, Object version) {
        Jwt jwt = jwtDecoder.decode(original);
        Map<String, Object> claims = new HashMap<>(jwt.getClaims());
        if (version == null) {
            claims.remove("access_version");
        } else {
            claims.put("access_version", version);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().claims(values -> values.putAll(claims)).build()))
                .getTokenValue();
    }

    private void assertAccessStatus(String token, int expected) throws Exception {
        tenantContext.clear();
        try {
            var response = mockMvc.perform(get("/client/profile").with(anonymous())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().is(expected));
            if (expected == 401) {
                response.andExpect(content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                        .andExpect(jsonPath("$.requestId").isNotEmpty());
            }
        } finally {
            authenticate("USER_MANAGE", "USER_READ");
        }
    }

    private IdempotencyResult<AdminUserResponse> changeStatus(UserAccountStatus status,
            String key) {
        return service.updateStatus(target.id(), new AdminUserStatusUpdateRequest(status), key);
    }

    private List<String> runConcurrent(Callable<String> first, Callable<String> second)
            throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final Future<String> firstResult = executor.submit(
                    () -> callInContext(first, ready, start));
            final Future<String> secondResult = executor.submit(
                    () -> callInContext(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String callInContext(Callable<String> action, CountDownLatch ready,
            CountDownLatch start) throws Exception {
        authenticate("USER_MANAGE", "USER_READ");
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return action.call();
        } finally {
            clearContexts();
        }
    }

    private AdminUserResponse currentUser(UUID id) {
        return queryService.list(null, null, 0, 100, null).content().stream()
                .filter(user -> user.id().equals(id)).findFirst().orElseThrow();
    }

    private String login(ManagedUser user) throws Exception {
        return loginTokens(user).path("refreshToken").asText();
    }

    private JsonNode loginTokens(ManagedUser user) throws Exception {
        tenantContext.clear();
        try {
            String response = mockMvc.perform(post("/auth/login").with(anonymous())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "tenantSlug", user.slug(), "email", user.email(),
                                    "password", PASSWORD))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(response);
        } finally {
            authenticate("USER_MANAGE", "USER_READ");
        }
    }

    private void assertRefreshStatus(String token, int expected) throws Exception {
        tenantContext.clear();
        try {
            mockMvc.perform(post("/auth/refresh").with(anonymous())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("refreshToken", token))))
                    .andExpect(status().is(expected));
        } finally {
            authenticate("USER_MANAGE", "USER_READ");
        }
    }

    private void authenticate(String... permissions) {
        tenantContext.setTenantId(tenantId);
        TestSecurityContextHolder.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), null,
                        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private UUID createActor(UUID id) {
        tenantContext.setTenantId(id);
        UserEntity user = userRepository.save(new UserEntity(id, "actor@example.com",
                "fixture-hash", UserStatus.ACTIVE));
        RoleEntity role = roleRepository.findByTenantIdAndCode(id, "ADMIN").orElseThrow();
        userRoleRepository.save(new UserRoleEntity(new UserRoleId(user.getId(), role.getId()), id));
        return user.getId();
    }

    private static void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private record ManagedUser(UUID id, String email, String slug) {
    }
}

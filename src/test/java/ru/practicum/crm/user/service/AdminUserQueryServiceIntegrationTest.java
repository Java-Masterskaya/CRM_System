package ru.practicum.crm.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleRepository;

@WithMockUser(authorities = "USER_READ")
class AdminUserQueryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminUserQueryService service;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private UUID tenantId;
    private UUID otherTenantId;

    @BeforeEach
    void createTenants() {
        tenantContext.clear();
        tenantId = createTenant("users-a");
        otherTenantId = createTenant("users-b");
    }

    @AfterEach
    void clearTenant() {
        tenantContext.clear();
    }

    @Test
    void givenTwoTenantsAndDeletedUsers_whenListing_thenReturnsOnlyVisibleUsers() {
        final UserEntity active = createUser(tenantId, "a@example.com", UserStatus.ACTIVE,
                "OPERATOR");
        final UserEntity blocked = createUser(tenantId, "b@example.com", UserStatus.BLOCKED,
                "CLIENT");
        final UserEntity legacy = createUser(tenantId, "c@example.com", UserStatus.ACTIVE);
        UserEntity deleted = createUser(tenantId, "d@example.com", UserStatus.ACTIVE, "CLIENT");
        createUser(otherTenantId, "foreign@example.com", UserStatus.ACTIVE, "OPERATOR");
        tenantContext.setTenantId(tenantId);
        deleted.delete();
        userRepository.save(deleted);

        PageResponse<AdminUserResponse> result = service.list(null, null, null, null, null);

        assertThat(result.content()).extracting(AdminUserResponse::id)
                .containsExactly(active.getId(), blocked.getId(), legacy.getId());
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.content().get(1).status()).isEqualTo(UserAccountStatus.BLOCKED);
        assertThat(result.content().get(2).roles()).isEmpty();
    }

    @Test
    void givenMultipleRoles_whenFilteringAndPaging_thenCountsEachUserOnce() {
        final UserEntity operator = createUser(tenantId, "a@example.com", UserStatus.ACTIVE,
                "OPERATOR");
        createUser(tenantId, "b@example.com", UserStatus.BLOCKED, "OPERATOR");
        createUser(tenantId, "c@example.com", UserStatus.ACTIVE, "CLIENT");
        final UserEntity admin = createUser(tenantId, "d@example.com", UserStatus.ACTIVE,
                "ADMIN", "OPERATOR");
        createUser(otherTenantId, "other@example.com", UserStatus.ACTIVE, "OPERATOR");
        tenantContext.setTenantId(tenantId);

        PageResponse<AdminUserResponse> first = service.list("OPERATOR", UserAccountStatus.ACTIVE,
                0, 1, null);
        PageResponse<AdminUserResponse> second = service.list("OPERATOR", UserAccountStatus.ACTIVE,
                1, 1, null);

        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(first.content()).extracting(AdminUserResponse::id)
                .containsExactly(operator.getId());
        assertThat(second.content()).extracting(AdminUserResponse::id)
                .containsExactly(admin.getId());
        assertThat(second.content().get(0).roles()).containsExactly("ADMIN", "OPERATOR");
        assertThat(service.list(null, UserAccountStatus.BLOCKED, 0, 20, null).totalElements())
                .isEqualTo(1);
        assertThat(service.list("OPERATOR", null, 0, 20, null).totalElements()).isEqualTo(3);
    }

    @Test
    void givenTiedNames_whenPaging_thenUsesStableIdOrder() {
        List<String> expectedIds = Stream.of(
                        createUser(tenantId, "a@example.com", UserStatus.ACTIVE),
                        createUser(tenantId, "b@example.com", UserStatus.ACTIVE),
                        createUser(tenantId, "c@example.com", UserStatus.ACTIVE))
                .map(user -> user.getId().toString()).sorted().toList();
        tenantContext.setTenantId(tenantId);

        for (int page = 0; page < expectedIds.size(); page++) {
            PageResponse<AdminUserResponse> result = service.list(null, null, page, 1,
                    List.of("name,asc"));
            assertThat(result.content()).extracting(user -> user.id().toString())
                    .containsExactly(expectedIds.get(page));
            assertThat(result.totalElements()).isEqualTo(3);
        }
        assertThat(service.list(null, null, 0, 20, List.of("email,desc")).content())
                .extracting(AdminUserResponse::email)
                .containsExactly("c@example.com", "b@example.com", "a@example.com");
    }

    @Test
    void givenUnknownRoleOrDistantPage_whenListing_thenReturnsEmptyContent() {
        createUser(tenantId, "a@example.com", UserStatus.ACTIVE, "OPERATOR");
        tenantContext.setTenantId(tenantId);

        PageResponse<AdminUserResponse> unknown = service.list("UNKNOWN", null, 0, 20, null);
        PageResponse<AdminUserResponse> distant = service.list(null, null, 3, 1, null);

        assertThat(unknown.content()).isEmpty();
        assertThat(unknown.totalElements()).isZero();
        assertThat(distant.content()).isEmpty();
        assertThat(distant.totalElements()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void givenInvalidParameters_whenListing_thenReturnsValidationFailure(
            String role, Integer page, Integer size, List<String> sort) {
        tenantContext.setTenantId(tenantId);

        assertThatThrownBy(() -> service.list(role, null, page, size, sort))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of(" ", 0, 20, null),
                Arguments.of("ADMIN CLIENT", 0, 20, null),
                Arguments.of(null, -1, 20, null),
                Arguments.of(null, 0, 0, null),
                Arguments.of(null, 0, 101, null),
                Arguments.of(null, Integer.MAX_VALUE, 20, null),
                Arguments.of(null, 0, 20, List.of("passwordHash,asc")),
                Arguments.of(null, 0, 20, List.of("email,sideways")));
    }

    @Test
    @WithMockUser(authorities = {"REQUEST_READ_ALL", "USER_MANAGE"})
    void givenNoUserReadPermission_whenListing_thenDeniesAccess() {
        tenantContext.setTenantId(tenantId);

        assertThatThrownBy(() -> service.list(null, null, 0, 20, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void givenMissingTenantContext_whenListing_thenRejectsRequest() {
        tenantContext.clear();

        assertThatThrownBy(() -> service.list(null, null, 0, 20, null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private UUID createTenant(String slug) {
        tenantService.createTenant(new TenantDto("Арендатор " + slug, slug, true));
        return tenantService.findActiveBySlug(slug).id();
    }

    private UserEntity createUser(UUID targetTenantId, String email, UserStatus status,
            String... roleCodes) {
        tenantContext.setTenantId(targetTenantId);
        try {
            UserEntity user = userRepository.save(new UserEntity(targetTenantId, email,
                    "Пользователь", "test-password-hash", status));
            for (String code : roleCodes) {
                RoleEntity role = roleRepository.findByTenantIdAndCode(targetTenantId, code)
                        .orElseThrow();
                userRoleRepository.save(new UserRoleEntity(
                        new UserRoleId(user.getId(), role.getId()), targetTenantId));
            }
            return user;
        } finally {
            tenantContext.clear();
        }
    }
}

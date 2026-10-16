package ru.practicum.crm.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.ClientProfileDto;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UpdateClientProfileRequest;
import ru.practicum.crm.user.api.dto.UserDto;
import ru.practicum.crm.user.context.UserContext;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;

class UserServiceImplIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_PASSWORD = "StrongPassword1!";
    private static final String NEW_VALID_PASSWORD = "AnotherPassword2@";
    @MockBean
    private TenantContext tenantContext;

    @Autowired
    private UserService userService;

    @Autowired
    private ru.practicum.crm.user.service.UserService clientUserService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private Validator validator;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private UserContext userContext;

    @Test
    void create_withValidPassword_persistsEncodedPasswordAndReturnsUser() {
        UUID tenantId = createTenant("Test Tenant");

        UserDto result = userService.create(new CreateUserRequest(
                "user@example.com",
                VALID_PASSWORD
        ));

        UserEntity savedUser = findUser(tenantId, "user@example.com");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(VALID_PASSWORD);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, savedUser.getPasswordHash()))
                .isTrue();
        assertThat(result).isEqualTo(new UserDto("user@example.com", UserStatus.ACTIVE));
    }

    @Test
    void create_withSamePasswordForDifferentUsers_persistsDifferentHashes() {
        UUID tenantId = createTenant("Test Tenant");
        userService.create(new CreateUserRequest(
                "first@example.com",
                VALID_PASSWORD
        ));
        userService.create(new CreateUserRequest(
                "second@example.com",
                VALID_PASSWORD
        ));

        UserEntity firstUser = findUser(tenantId, "first@example.com");
        UserEntity secondUser = findUser(tenantId, "second@example.com");
        assertThat(firstUser.getPasswordHash()).isNotEqualTo(secondUser.getPasswordHash());
        assertThat(passwordEncoder.matches(VALID_PASSWORD, firstUser.getPasswordHash()))
                .isTrue();
        assertThat(passwordEncoder.matches(VALID_PASSWORD, secondUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    void create_withInvalidPassword_throwsValidationErrorAndDoesNotPersistUser() {
        UUID tenantId = createTenant("Test Tenant");

        assertThatThrownBy(() -> userService.create(new CreateUserRequest(
                "user@example.com",
                "weak"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(userRepository.findAllActiveByTenantId(tenantId)).isEmpty();
    }

    @Test
    void changePassword_withValidPasswords_persistsNewPasswordHash() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);
        when(userContext.getCurrentUserId()).thenReturn(user.getId());
        String originalHash = user.getPasswordHash();

        userService.changePassword(new ChangePasswordRequest(
                VALID_PASSWORD,
                NEW_VALID_PASSWORD
        ));

        UserEntity updatedUser = reloadUser(user.getId(), user.getTenantId());
        assertThat(updatedUser.getPasswordHash()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches(
                NEW_VALID_PASSWORD,
                updatedUser.getPasswordHash()
        )).isTrue();
    }

    @Test
    void changePassword_withIncorrectCurrentPassword_throwsInvalidCredentials() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);
        when(userContext.getCurrentUserId()).thenReturn(user.getId());
        String originalHash = user.getPasswordHash();

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest(
                "WrongPassword1!",
                NEW_VALID_PASSWORD
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        UserEntity unchangedUser = reloadUser(user.getId(), user.getTenantId());
        assertThat(unchangedUser.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, unchangedUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    void changePassword_withInvalidNewPassword_throwsValidationErrorAndKeepsOldPassword() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);
        when(userContext.getCurrentUserId()).thenReturn(user.getId());
        String originalHash = user.getPasswordHash();

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest(
                VALID_PASSWORD,
                "weak"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));

        UserEntity unchangedUser = reloadUser(user.getId(), user.getTenantId());
        assertThat(unchangedUser.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, unchangedUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    void create_withPasswordLongerThan72Bytes_throwsValidationErrorAndDoesNotPersistUser() {
        UUID tenantId = createTenant("Test Tenant");
        String tooLongPassword = "A" + "a".repeat(70) + "1!";

        assertThat(tooLongPassword).hasSize(73);
        assertThatThrownBy(() -> userService.create(new CreateUserRequest("user@example.com",
                tooLongPassword
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(userRepository.findAllActiveByTenantId(tenantId)).isEmpty();
    }

    @Test
    void authenticate_withValidCredentials_returnsAuthenticatedUser() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);

        AuthenticatedUserDto result = userService.authenticate(
                user.getTenantId(),
                user.getEmail(),
                VALID_PASSWORD
        );

        assertThat(result).isNotNull();
        assertThat(result.tenantId()).isEqualTo(user.getTenantId());
    }

    @Test
    void authenticate_withIncorrectPassword_throwsInvalidCredentials() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);

        assertThatThrownBy(() -> userService.authenticate(
                user.getTenantId(),
                user.getEmail(),
                "WrongPassword1!"
        ))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void authenticate_withUnknownEmail_throwsInvalidCredentialsAndChecksPassword() {
        UUID tenantId = createTenant("Test Tenant");

        assertThatThrownBy(() -> userService.authenticate(
                tenantId,
                "unknown@example.com",
                VALID_PASSWORD
        ))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(passwordEncoder, times(1))
                .matches(eq(VALID_PASSWORD), anyString());
    }

    @Test
    void authenticate_withUserFromAnotherTenant_throwsInvalidCredentials() {
        UUID firstTenantId = createTenant("Test Tenant1");

        UserEntity user = userRepository.save(new UserEntity(
                firstTenantId,
                "user@example.com",
                passwordEncoder.encode(VALID_PASSWORD),
                UserStatus.ACTIVE
        ));

        UUID secondTenantId = createTenant("Test Tenant2");

        assertThatThrownBy(() -> userService.authenticate(
                secondTenantId,
                user.getEmail(),
                VALID_PASSWORD
        ))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void registerClient_persistsPasswordHashAndAssignsOnlyClientRole() {
        String slug = uniqueTenantSlug();
        UUID tenantId = createTenantWithSlug(slug);

        clientUserService.registerClient(registration(
                slug,
                "client@example.com",
                "Client"
        ));

        UserEntity savedUser = findUser(tenantId, "client@example.com");
        assertThat(passwordEncoder.matches(VALID_PASSWORD, savedUser.getPasswordHash()))
                .isTrue();

        List<String> assignedRoles = jdbcTemplate.queryForList(
                """
                SELECT r.code
                FROM roles r
                JOIN user_roles ur ON ur.role_id = r.id AND ur.tenant_id = r.tenant_id
                WHERE ur.user_id = ? AND ur.tenant_id = ?
                """,
                String.class,
                savedUser.getId(),
                tenantId
        );
        assertThat(assignedRoles).containsExactly("CLIENT");
    }

    @Test
    void registerClient_rejectsDuplicateEmailButAllowsItInAnotherTenant() {
        String firstSlug = uniqueTenantSlug();
        UUID firstTenantId = createTenantWithSlug(firstSlug);
        clientUserService.registerClient(registration(
                firstSlug,
                "Duplicate@example.com",
                "First client"
        ));

        assertThatThrownBy(() -> clientUserService.registerClient(registration(
                firstSlug,
                "duplicate@EXAMPLE.com",
                "Duplicate client"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ALREADY_EXISTS));
        assertThat(userRepository.findAllActiveByTenantId(firstTenantId)).hasSize(1);

        String secondSlug = uniqueTenantSlug();
        UUID secondTenantId = createTenantWithSlug(secondSlug);
        clientUserService.registerClient(registration(
                secondSlug,
                "duplicate@example.com",
                "Other tenant client"
        ));

        assertThat(userRepository.findAllActiveByTenantId(secondTenantId)).hasSize(1);
    }

    @Test
    void registerClient_trimsEmailBeforeBeanValidation() {
        ClientRegistrationRequest request = new ClientRegistrationRequest(
                uniqueTenantSlug(),
                "  client@example.com  ",
                VALID_PASSWORD,
                "Client"
        );

        assertThat(request.email()).isEqualTo("client@example.com");
        assertThat(validator.validate(request)).isEmpty();
    }

    /** Права на профиль проверяются на методах сервиса (T-030) — тест их даёт. */
    @Test
    @WithMockUser(authorities = {"PROFILE_READ_OWN", "PROFILE_EDIT_OWN"})
    void clientProfile_readsAndUpdatesOnlyCurrentUsersNameAndPhone() {
        String slug = uniqueTenantSlug();
        UUID tenantId = createTenantWithSlug(slug);
        clientUserService.registerClient(registration(
                slug,
                "owner@example.com",
                "Owner"
        ));
        clientUserService.registerClient(registration(
                slug,
                "other@example.com",
                "Other user"
        ));

        UserEntity owner = findUser(tenantId, "owner@example.com");
        UserEntity otherUser = findUser(tenantId, "other@example.com");
        when(userContext.getCurrentUserId()).thenReturn(owner.getId());

        assertThat(clientUserService.getClientProfile())
                .isEqualTo(new ClientProfileDto("Owner", "owner@example.com", null));

        ClientProfileDto updated = clientUserService.updateClientProfile(
                new UpdateClientProfileRequest("Updated Owner", "+1 234 567 8901")
        );

        assertThat(updated).isEqualTo(new ClientProfileDto(
                "Updated Owner",
                "owner@example.com",
                "+1 234 567 8901"
        ));
        assertThat(reloadUser(owner.getId(), tenantId).getEmail())
                .isEqualTo("owner@example.com");
        UserEntity unchangedOtherUser = reloadUser(otherUser.getId(), tenantId);
        assertThat(unchangedOtherUser.getName()).isEqualTo("Other user");
        assertThat(unchangedOtherUser.getPhone()).isNull();
    }

    /** Условие то же, что при входе: активен и не удалён (нужно обмену refresh-токена). */
    @Test
    void canLogIn_isTrueOnlyForActiveNotDeletedUserOfThatTenant() {
        UserEntity user = createUserWithPassword(VALID_PASSWORD);
        UUID tenantId = user.getTenantId();

        assertThat(userService.canLogIn(tenantId, user.getId())).as("активный").isTrue();
        assertThat(userService.canLogIn(UUID.randomUUID(), user.getId()))
                .as("другой арендатор").isFalse();
        assertThat(userService.canLogIn(tenantId, UUID.randomUUID()))
                .as("неизвестный").isFalse();

        jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", user.getId());
        assertThat(userService.canLogIn(tenantId, user.getId())).as("заблокирован").isFalse();

        jdbcTemplate.update("UPDATE users SET status = 'ACTIVE', deleted_at = now() WHERE id = ?",
                user.getId());
        assertThat(userService.canLogIn(tenantId, user.getId())).as("удалён").isFalse();
    }

    private UUID createTenant(String name) {
        UUID id = UUID.randomUUID();
        String slug = "tenant-" + id.toString().replace("-", "");

        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())",
                id, name, slug
        );

        when(tenantContext.getCurrentTenantId()).thenReturn(id);
        return id;
    }

    private UUID createTenantWithSlug(String slug) {
        when(tenantContext.getCurrentTenantId()).thenReturn(null);
        tenantService.createTenant(new TenantDto("Registration tenant", slug, true));
        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE slug = ?",
                UUID.class,
                slug
        );
        when(tenantContext.getCurrentTenantId()).thenReturn(tenantId);
        return tenantId;
    }

    private String uniqueTenantSlug() {
        return "registration-" + UUID.randomUUID().toString().replace("-", "");
    }

    private ClientRegistrationRequest registration(
            String tenantSlug,
            String email,
            String name
    ) {
        return new ClientRegistrationRequest(tenantSlug, email, VALID_PASSWORD, name);
    }

    private UserEntity createUserWithPassword(String password) {
        UUID tenantId = createTenant("Test Tenant");
        return userRepository.save(new UserEntity(
                tenantId,
                "user@example.com",
                passwordEncoder.encode(password),
                UserStatus.ACTIVE
        ));
    }

    private UserEntity findUser(UUID tenantId, String email) {
        return userRepository.findAllActiveByTenantId(tenantId).stream()
                .filter(user -> user.getEmail().equals(email))
                .findFirst()
                .orElseThrow();
    }

    private UserEntity reloadUser(UUID userId, UUID tenantId) {
        return userRepository.findById(userId, tenantId).orElseThrow();
    }
}

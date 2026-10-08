package ru.practicum.crm.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
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

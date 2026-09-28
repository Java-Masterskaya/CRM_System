package ru.practicum.crm.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.context.TenantContext;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UserDto;
import ru.practicum.crm.user.context.UserContext;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.service.UserService;

class UserServiceImplIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_PASSWORD = "StrongPassword1!";
    private static final String NEW_VALID_PASSWORD = "AnotherPassword2@";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private TenantContext tenantContext;

    @MockBean
    private UserContext userContext;

    @Test
    void create_withValidPassword_persistsEncodedPasswordAndReturnsUser() {
        Tenant tenant = createTenant();

        UserDto result = userService.create(new CreateUserRequest(
                "user@example.com",
                "User",
                VALID_PASSWORD
        ));

        UserEntity savedUser = findUser(tenant.getId(), "user@example.com");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(VALID_PASSWORD);
        assertThat(passwordEncoder.matches(VALID_PASSWORD, savedUser.getPasswordHash()))
                .isTrue();
        assertThat(result).isEqualTo(new UserDto("user@example.com", UserStatus.ACTIVE));
    }

    @Test
    void create_withSamePasswordForDifferentUsers_persistsDifferentHashes() {
        Tenant tenant = createTenant();
        userService.create(new CreateUserRequest(
                "first@example.com",
                "First",
                VALID_PASSWORD
        ));
        userService.create(new CreateUserRequest(
                "second@example.com",
                "Second",
                VALID_PASSWORD
        ));

        UserEntity firstUser = findUser(tenant.getId(), "first@example.com");
        UserEntity secondUser = findUser(tenant.getId(), "second@example.com");
        assertThat(firstUser.getPasswordHash()).isNotEqualTo(secondUser.getPasswordHash());
        assertThat(passwordEncoder.matches(VALID_PASSWORD, firstUser.getPasswordHash()))
                .isTrue();
        assertThat(passwordEncoder.matches(VALID_PASSWORD, secondUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    void create_withInvalidPassword_throwsValidationErrorAndDoesNotPersistUser() {
        Tenant tenant = createTenant();

        assertThatThrownBy(() -> userService.create(new CreateUserRequest(
                "user@example.com",
                "User",
                "weak"
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(userRepository.findAllActiveByTenantId(tenant.getId())).isEmpty();
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

    private Tenant createTenant() {
        Tenant tenant = tenantRepository.save(new Tenant("User service integration tenant"));
        when(tenantContext.getCurrentTenantId()).thenReturn(tenant.getId());
        return tenant;
    }

    private UserEntity createUserWithPassword(String password) {
        Tenant tenant = createTenant();
        UserEntity user = userRepository.save(new UserEntity(
                tenant.getId(),
                "user@example.com",
                passwordEncoder.encode(password),
                UserStatus.ACTIVE
        ));
        return user;
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

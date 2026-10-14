package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Выход и отзыв всех токенов пользователя на реальной базе (T-029, DoD #30). Выход по HTTP
 * проверяет {@code AuthLogoutIntegrationTest}.
 */
@Import(AuthTestFixture.class)
class RefreshTokenRevocationIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokens;

    @Autowired
    private AuthTestFixture authTestFixture;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = authTestFixture.createTenant("refresh-revocation");
        user = authTestFixture.createUser(tenant, "user@example.com", PASSWORD);
        authTestFixture.createUser(tenant, "colleague@example.com", PASSWORD);
    }

    /**
     * Выход завершает вход целиком: ни свежий токен, ни ранее использованные токены этого
     * входа не обмениваются. Другой вход того же пользователя продолжает работать.
     */
    @Test
    void logout_endsOnlyThatLogin() {
        String first = login("user@example.com");
        String latest = authService.refresh(first).refreshToken();
        final String otherDevice = login("user@example.com");

        authService.logout(latest);

        assertRejected(latest);
        assertRejected(first);
        assertThat(authService.refresh(otherDevice).refreshToken()).isNotBlank();
    }

    /** DoD: отзыв всех токенов пользователя прекращает обновление на всех его входах. */
    @Test
    void revokeAll_stopsRefreshOnEveryLoginOfUserOnly() {
        String phone = login("user@example.com");
        String laptop = authService.refresh(login("user@example.com")).refreshToken();
        final String colleague = login("colleague@example.com");

        assertThat(refreshTokens.revokeAll(user.id())).as("три токена двух входов").isEqualTo(3);

        assertRejected(phone);
        assertRejected(laptop);
        assertThat(authService.refresh(colleague).refreshToken())
                .as("другой пользователь не затронут").isNotBlank();
        assertThat(authService.refresh(login("user@example.com")).refreshToken())
                .as("новый вход работает").isNotBlank();
    }

    private String login(String email) {
        return authService.authenticate(new LoginRequest(tenant.slug(), email, PASSWORD))
                .refreshToken();
    }

    private void assertRejected(String rawToken) {
        assertThatThrownBy(() -> authService.refresh(rawToken))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}

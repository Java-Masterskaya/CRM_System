package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Доработка обмена refresh-токена по ревью #185 и #190 на реальной базе: заблокированный
 * пользователь и неактивный арендатор не обмениваются, срок входа абсолютный, сбой выпуска
 * access-токена откатывает ротацию.
 */
@Import(AuthTestFixture.class)
class RefreshTokenHardeningIntegrationTest extends BaseIntegrationTest {

    private static final String EMAIL = "hardening@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private JwtTokenService jwtTokenService;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = authTestFixture.createTenant("refresh-hardening");
        user = authTestFixture.createUser(tenant, EMAIL, PASSWORD);
    }

    /**
     * Заблокирован после входа — обмен отклоняется тем же 401, что и вход, и ротация
     * откатывается: токен не помечен использованным, нового не выдано. После разблокировки тот
     * же токен снова обменивается — повтор не принят за кражу.
     */
    @Test
    void refresh_whenUserBlockedAfterLogin_isRejectedWithoutTouchingToken() {
        String token = login();
        authTestFixture.blockUser(tenant.id(), user.id());

        assertRejected(token);

        assertThat(usedAt(token)).as("ротация откатилась").isNull();
        assertThat(tokenCount()).isEqualTo(1);
        jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user.id());
        assertThat(authService.refresh(token).refreshToken()).isNotBlank();
    }

    @Test
    void refresh_whenUserDeletedAfterLogin_isRejected() {
        String token = login();
        authTestFixture.deleteUser(tenant.id(), user.id());

        assertRejected(token);
        assertThat(usedAt(token)).isNull();
    }

    @Test
    void refresh_whenTenantDeactivatedAfterLogin_isRejectedWithoutTouchingToken() {
        String token = login();
        jdbcTemplate.update("UPDATE tenants SET active = false WHERE id = ?", tenant.id());

        assertRejected(token);

        assertThat(usedAt(token)).isNull();
        assertThat(tokenCount()).isEqualTo(1);
    }

    /** Срок входа абсолютный: обмен не продлевает его, новый токен наследует срок родителя. */
    @Test
    void refresh_keepsExpiryOfLoginInsteadOfExtendingIt() {
        String first = login();
        String second = authService.refresh(first).refreshToken();
        String third = authService.refresh(second).refreshToken();

        assertThat(expiresAt(third)).isEqualTo(expiresAt(second)).isEqualTo(expiresAt(first));
    }

    /**
     * Выпуск access-токена сорвался после ротации: ротация откатывается вместе с ним, старый
     * токен остаётся рабочим, и повтор с ним не принимается за кражу.
     */
    @Test
    void refresh_whenAccessTokenCannotBeIssued_rollsRotationBack() {
        String token = login();
        doThrow(new IllegalStateException("ключ недоступен"))
                .when(jwtTokenService).createAccessToken(any(), any(), any());

        assertThatThrownBy(() -> authService.refresh(token))
                .isInstanceOf(IllegalStateException.class);

        assertThat(usedAt(token)).as("ротация откатилась").isNull();
        assertThat(tokenCount()).isEqualTo(1);
        reset(jwtTokenService);
        assertThat(authService.refresh(token).refreshToken()).as("повтор — не кража")
                .isNotBlank();
    }

    private String login() {
        return authService.authenticate(new LoginRequest(tenant.slug(), EMAIL, PASSWORD))
                .refreshToken();
    }

    private void assertRejected(String rawToken) {
        assertThatThrownBy(() -> authService.refresh(rawToken))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private OffsetDateTime usedAt(String rawToken) {
        return jdbcTemplate.queryForObject(
                "SELECT used_at FROM refresh_tokens WHERE token_hash = ?", OffsetDateTime.class,
                sha256(rawToken));
    }

    private OffsetDateTime expiresAt(String rawToken) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE token_hash = ?",
                OffsetDateTime.class, sha256(rawToken));
    }

    private Integer tokenCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class);
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

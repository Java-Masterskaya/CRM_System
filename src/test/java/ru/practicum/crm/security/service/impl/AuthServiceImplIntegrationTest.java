package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.domain.RefreshToken;
import ru.practicum.crm.security.repository.RefreshTokenRepository;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.testsupport.AuthTestFixture;

@Import(AuthTestFixture.class)
class AuthServiceImplIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String EMAIL = "user@example.com";

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticate_shouldReturnAccessAndRefreshTokens() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-success");

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.accessToken()).isNotEqualTo(response.refreshToken());
    }

    @Test
    void authenticate_shouldIncludeExpectedClaimsInAccessToken() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-claims");

        AuthTestFixture.TestUser user =
                authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        authTestFixture.grantPermission(tenant, user, "REQUEST_READ");

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        Jwt accessToken = jwtDecoder.decode(response.accessToken());

        assertThat(accessToken.getSubject())
                .isEqualTo(user.id().toString());
        assertThat(accessToken.getClaimAsString("tenant_id"))
                .isEqualTo(tenant.id().toString());
        assertThat(accessToken.getClaimAsStringList("permissions"))
                .containsExactly("REQUEST_READ");
        assertThat(accessToken.getIssuedAt()).isNotNull();
        assertThat(accessToken.getExpiresAt()).isNotNull();
        assertThat(accessToken.getExpiresAt())
                .isAfter(accessToken.getIssuedAt());
    }

    @Test
    void authenticate_shouldPersistRefreshTokenHash()
            throws NoSuchAlgorithmException {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-refresh-hash");

        AuthTestFixture.TestUser user =
                authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        String refreshTokenHash = sha256(response.refreshToken());

        RefreshToken persistedToken = refreshTokenRepository.findByTokenHash(refreshTokenHash)
                .orElseThrow();

        assertThat(response.refreshToken()).isNotEqualTo(refreshTokenHash);
        assertThat(refreshTokenRepository.findByTokenHash(response.refreshToken())).isEmpty();
        assertThat(persistedToken.getTokenHash()).isEqualTo(refreshTokenHash);
        assertThat(persistedToken.getUserId()).isEqualTo(user.id());
        assertThat(persistedToken.getTenantId()).isEqualTo(tenant.id());
        assertThat(persistedToken.getFamilyId()).isNotNull();
        assertThat(persistedToken.getCreatedAt()).isNotNull();
        assertThat(persistedToken.getExpiresAt()).isNotNull();
        assertThat(persistedToken.getUsedAt()).isNull();
        assertThat(persistedToken.getRevokedAt()).isNull();
    }

    @Test
    void authenticate_shouldRejectUnknownTenant() {
        assertInvalidCredentials(new LoginRequest("missing-tenant", EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRejectInactiveTenant() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-inactive", false);

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRejectInvalidCredentials() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-invalid-credentials");

        AuthTestFixture.TestUser user = authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        assertInvalidCredentials(authRequest(tenant, "unknown@example.com", PASSWORD));
        assertInvalidCredentials(authRequest(tenant, EMAIL, "WrongPassword2!"));

        authTestFixture.blockUser(tenant.id(), user.id());

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRejectDeletedUser() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("auth-deleted");

        AuthTestFixture.TestUser user =
                authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        authTestFixture.deleteUser(tenant.id(), user.id());

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRespectTenantIsolation() {
        AuthTestFixture.TestTenant firstTenant =
                authTestFixture.createTenant("auth-isolation-first");

        AuthTestFixture.TestTenant secondTenant =
                authTestFixture.createTenant("auth-isolation-second");

        AuthTestFixture.TestUser firstUser =
                authTestFixture.createUser(firstTenant, EMAIL, PASSWORD);

        AuthTestFixture.TestUser secondUser =
                authTestFixture.createUser(secondTenant, EMAIL, "DifferentPassword2!");

        AuthResponse firstResponse = authenticate(firstTenant, EMAIL, PASSWORD);

        AuthResponse secondResponse =
                authenticate(secondTenant, EMAIL, "DifferentPassword2!");

        assertThat(jwtDecoder.decode(firstResponse.accessToken()).getSubject())
                .isEqualTo(firstUser.id().toString());

        assertThat(jwtDecoder.decode(secondResponse.accessToken()).getSubject())
                .isEqualTo(secondUser.id().toString());

        assertInvalidCredentials(
                authRequest(firstTenant, EMAIL, "DifferentPassword2!"));
    }

    private AuthResponse authenticate(
            AuthTestFixture.TestTenant tenant,
            String email,
            String password
    ) {
        return authService.authenticate(authRequest(tenant, email, password));
    }

    private LoginRequest authRequest(
            AuthTestFixture.TestTenant tenant,
            String email,
            String password
    ) {
        return new LoginRequest(tenant.slug(), email, password);
    }

    private void assertInvalidCredentials(LoginRequest request) {
        assertThatThrownBy(() -> authService.authenticate(request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    private int refreshTokenCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens",
                Integer.class
        );

        return Objects.requireNonNull(
                count,
                "COUNT(*) query returned null"
        );
    }

    private String sha256(String token)
            throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest
                .getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}

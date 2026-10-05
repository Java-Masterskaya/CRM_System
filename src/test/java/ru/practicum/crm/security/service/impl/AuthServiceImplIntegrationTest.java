package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;

class AuthServiceImplIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String EMAIL = "user@example.com";

    @Autowired
    private AuthService authService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticate_shouldReturnAccessAndRefreshTokens() {
        Tenant tenant = createTenant("auth-success");
        createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.accessToken())
                .isNotEqualTo(response.refreshToken());
    }

    @Test
    void authenticate_shouldIncludeExpectedClaimsInAccessToken() {
        Tenant tenant = createTenant("auth-claims");
        UserEntity user = createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        grantPermission(tenant.getId(), user.getId(), "REQUEST_READ");

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        Jwt accessToken = jwtDecoder.decode(response.accessToken());

        assertThat(accessToken.getSubject()).isEqualTo(user.getId().toString());
        assertThat(accessToken.getClaimAsString("tenant_id"))
                .isEqualTo(tenant.getId().toString());
        assertThat(accessToken.getClaimAsStringList("permissions")).containsExactly("REQUEST_READ");
        assertThat(accessToken.getIssuedAt()).isNotNull();
        assertThat(accessToken.getExpiresAt()).isNotNull();
        assertThat(accessToken.getExpiresAt()).isAfter(accessToken.getIssuedAt());
    }

    @Test
    void authenticate_shouldPersistRefreshTokenHash()
            throws NoSuchAlgorithmException {
        Tenant tenant = createTenant("auth-refresh-hash");
        UserEntity user = createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        AuthResponse response = authenticate(tenant, EMAIL, PASSWORD);

        String refreshTokenHash = sha256(response.refreshToken());

        RefreshToken persistedToken = refreshTokenRepository
                .findByTokenHash(refreshTokenHash)
                .orElseThrow();

        assertThat(response.refreshToken()).isNotEqualTo(refreshTokenHash);
        assertThat(refreshTokenRepository.findByTokenHash(response.refreshToken())).isEmpty();
        assertThat(persistedToken.getTokenHash()).isEqualTo(refreshTokenHash);
        assertThat(persistedToken.getUserId()).isEqualTo(user.getId());
        assertThat(persistedToken.getTenantId()).isEqualTo(tenant.getId());
        assertThat(persistedToken.getFamilyId()).isNotNull();
        assertThat(persistedToken.getCreatedAt()).isNotNull();
        assertThat(persistedToken.getExpiresAt()).isNotNull();
        assertThat(persistedToken.getUsedAt()).isNull();
        assertThat(persistedToken.getRevokedAt()).isNull();
    }

    @Test
    void authenticate_shouldRejectUnknownTenant() {
        assertInvalidCredentials(new LoginRequest("missing-tenant", EMAIL, PASSWORD));

        assertThat(refreshTokenCount())
                .isZero();
    }

    @Test
    void authenticate_shouldRejectInactiveTenant() {
        Tenant tenant = createTenant("auth-inactive", false);
        createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRejectInvalidCredentials() {
        Tenant tenant = createTenant("auth-invalid-credentials");

        UserEntity user = createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        assertInvalidCredentials(authRequest(tenant, "unknown@example.com", PASSWORD));
        assertInvalidCredentials(authRequest(tenant, EMAIL, "WrongPassword2!"));

        user.block();
        userRepository.save(user);

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRejectDeletedUser() {
        Tenant tenant = createTenant("auth-deleted");

        UserEntity user = createUser(tenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        user.delete();
        userRepository.save(user);

        assertInvalidCredentials(authRequest(tenant, EMAIL, PASSWORD));
        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void authenticate_shouldRespectTenantIsolation() {
        Tenant firstTenant = createTenant("auth-isolation-first");
        Tenant secondTenant = createTenant("auth-isolation-second");

        UserEntity firstUser = createUser(firstTenant, EMAIL, PASSWORD, UserStatus.ACTIVE);

        UserEntity secondUser = createUser(
                secondTenant, EMAIL, "DifferentPassword2!", UserStatus.ACTIVE);

        AuthResponse firstResponse = authenticate(firstTenant, EMAIL, PASSWORD);

        AuthResponse secondResponse = authenticate(secondTenant, EMAIL, "DifferentPassword2!");

        assertThat(jwtDecoder.decode(firstResponse.accessToken()).getSubject())
                .isEqualTo(firstUser.getId().toString());

        assertThat(jwtDecoder.decode(secondResponse.accessToken()).getSubject())
                .isEqualTo(secondUser.getId().toString());

        assertInvalidCredentials(authRequest(firstTenant, EMAIL, "DifferentPassword2!"));
    }

    private Tenant createTenant(String slug) {
        return createTenant(slug, true);
    }

    private Tenant createTenant(String slug, boolean active) {
        return tenantRepository.save(
                new Tenant(
                        "Integration test tenant",
                        slug,
                        active
                )
        );
    }

    private UserEntity createUser(
            Tenant tenant,
            String email,
            String password,
            UserStatus status
    ) {
        return userRepository.save(
                new UserEntity(
                        tenant.getId(),
                        email,
                        passwordEncoder.encode(password),
                        status
                )
        );
    }

    private void grantPermission(
            UUID tenantId,
            UUID userId,
            String permissionCode
    ) {
        UUID roleId = UUID.randomUUID();
        UUID permissionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO roles (
                    id,
                    tenant_id,
                    code,
                    name,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                roleId,
                tenantId,
                "AUTH_TEST_ROLE",
                "Authentication test role"
        );

        jdbcTemplate.update(
                """
                INSERT INTO permissions (
                    id,
                    tenant_id,
                    code,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, NOW(), NOW())
                """,
                permissionId,
                tenantId,
                permissionCode
        );

        jdbcTemplate.update(
                """
                INSERT INTO user_roles (
                    tenant_id,
                    user_id,
                    role_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                userId,
                roleId
        );

        jdbcTemplate.update(
                """
                INSERT INTO role_permissions (
                    tenant_id,
                    role_id,
                    permission_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                roleId,
                permissionId
        );
    }

    private AuthResponse authenticate(
            Tenant tenant,
            String email,
            String password
    ) {
        return authService.authenticate(
                authRequest(tenant, email, password)
        );
    }

    private LoginRequest authRequest(
            Tenant tenant,
            String email,
            String password
    ) {
        return new LoginRequest(
                tenant.getSlug(),
                email,
                password
        );
    }

    private void assertInvalidCredentials(LoginRequest request) {
        assertThatThrownBy(() -> authService.authenticate(request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    private int refreshTokenCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens",
                Integer.class
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

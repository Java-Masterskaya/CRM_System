package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class RefreshTokenRotationIntegrationTest extends BaseIntegrationTest {

    private static final String EMAIL = "refresh-user@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void rotate_shouldIssueNewPairAndPreserveTokenFamily() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("refresh-family");

        AuthTestFixture.TestUser user =
                authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        authTestFixture.grantPermission(tenant, user, "REQUEST_READ");

        AuthResponse initial = authenticate(tenant);

        RefreshToken originalToken = findToken(initial.refreshToken());

        AuthResponse rotated = authService.refresh(initial.refreshToken());

        RefreshToken usedToken = findToken(initial.refreshToken());
        RefreshToken replacementToken = findToken(rotated.refreshToken());

        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.accessToken()).isNotEqualTo(rotated.refreshToken());

        assertThat(rotated.refreshToken())
                .isNotEqualTo(initial.refreshToken());

        assertThat(usedToken.getUsedAt()).isNotNull();
        assertThat(replacementToken.getUsedAt()).isNull();
        assertThat(replacementToken.getRevokedAt()).isNull();

        assertThat(replacementToken.getFamilyId())
                .isEqualTo(originalToken.getFamilyId());
        assertThat(replacementToken.getUserId()).isEqualTo(user.id());
        assertThat(replacementToken.getTenantId()).isEqualTo(tenant.id());

        Jwt accessToken = jwtDecoder.decode(rotated.accessToken());

        assertThat(accessToken.getSubject()).isEqualTo(user.id().toString());
        assertThat(accessToken.getClaimAsString("tenant_id"))
                .isEqualTo(tenant.id().toString());
        assertThat(accessToken.getClaimAsStringList("permissions"))
                .contains("REQUEST_READ");

        assertThat(refreshTokenCount()).isEqualTo(2);
    }

    @Test
    void rotate_shouldRejectReuseOfPreviousRefreshToken() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("refresh-reuse");

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse initial = authenticate(tenant);

        authService.refresh(initial.refreshToken());

        assertRejected(initial.refreshToken());

        assertThat(refreshTokenCount()).isEqualTo(2);
    }

    @Test
    void rotate_shouldRejectUnknownRefreshToken() {
        assertRejected("unknown-refresh-token");

        assertThat(refreshTokenCount()).isZero();
    }

    @Test
    void rotate_shouldRejectExpiredRefreshToken() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("refresh-expired");

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse initial = authenticate(tenant);

        jdbcTemplate.update(
                """
                        UPDATE refresh_tokens
                        SET expires_at = NOW() - INTERVAL '1 second'
                        WHERE token_hash = ?
                """,
                sha256(initial.refreshToken())
        );

        assertRejected(initial.refreshToken());

        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    @Test
    void rotate_shouldRejectRevokedRefreshToken() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("refresh-revoked");

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse initial = authenticate(tenant);

        jdbcTemplate.update(
                """
                        UPDATE refresh_tokens
                        SET revoked_at = NOW()
                        WHERE token_hash = ?
                """,
                sha256(initial.refreshToken())
        );

        assertRejected(initial.refreshToken());

        assertThat(refreshTokenCount()).isEqualTo(1);
    }

    @Test
    void rotate_concurrently_shouldIssueOnlyOneNewPair() throws Exception {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant("refresh-concurrent");

        authTestFixture.createUser(tenant, EMAIL, PASSWORD);

        AuthResponse initial = authenticate(tenant);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(
                    () -> tryRefresh(initial.refreshToken(), ready, start)
            );

            Future<Boolean> second = executor.submit(
                    () -> tryRefresh(initial.refreshToken(), ready, start)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            boolean firstSucceeded = first.get(10, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(10, TimeUnit.SECONDS);

            int successCount = (firstSucceeded ? 1 : 0)
                    + (secondSucceeded ? 1 : 0);

            assertThat(successCount).isEqualTo(1);
            assertThat(refreshTokenCount()).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private AuthResponse authenticate(AuthTestFixture.TestTenant tenant) {
        return authService.authenticate(
                new LoginRequest(tenant.slug(), EMAIL, PASSWORD)
        );
    }

    private RefreshToken findToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow();
    }

    private void assertRejected(String rawToken) {
        assertThatThrownBy(() -> authService.refresh(rawToken))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHENTICATED)
                );
    }

    private boolean tryRefresh(
            String rawToken,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "Concurrent refresh test did not start in time"
            );
        }

        try {
            authService.refresh(rawToken);
            return true;
        } catch (ApiException exception) {
            assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
            return false;
        }
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

    private String sha256(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }
}

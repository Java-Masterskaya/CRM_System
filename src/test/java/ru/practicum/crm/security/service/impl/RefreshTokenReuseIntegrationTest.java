package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Повторное предъявление использованного refresh-токена на реальной базе (T-028, DoD #29).
 *
 * <p>Цепочка входа — токены одного входа: каждый обмен помечает предъявленный токен
 * использованным и выдаёт следующий с тем же {@code family_id}.
 */
@Import(AuthTestFixture.class)
class RefreshTokenReuseIntegrationTest extends BaseIntegrationTest {

    private static final String EMAIL = "reuse-user@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = authTestFixture.createTenant("refresh-reuse-detection");
        user = authTestFixture.createUser(tenant, EMAIL, PASSWORD);
    }

    /**
     * DoD: повторное предъявление использованного refresh отклоняется, и после этого ни один
     * ранее выданный refresh цепочки не обменивается — в том числе самый свежий. Отказ не
     * откатывает отзыв: цепочка отозвана в базе.
     */
    @Test
    void refresh_whenUsedTokenPresentedAgain_isRejectedAndRevokesWholeChain() {
        String first = login().refreshToken();
        String second = authService.refresh(first).refreshToken();
        final String latest = authService.refresh(second).refreshToken();

        assertRejected(first);

        assertThat(revokedAt(first)).isNotNull();
        assertThat(revokedAt(second)).isNotNull();
        assertThat(revokedAt(latest)).as("самый свежий токен цепочки").isNotNull();
        assertRejected(latest);
        assertThat(tokenCount()).as("новых токенов не выдано").isEqualTo(3);
    }

    /** DoD: доступ восстанавливается только повторным входом с логином и паролем. */
    @Test
    void refresh_afterChainRevoked_accessIsRestoredOnlyByNewLogin() {
        String first = login().refreshToken();
        final String latest = authService.refresh(first).refreshToken();
        assertRejected(first);

        String afterLogin = login().refreshToken();

        assertThat(familyOf(afterLogin)).as("новый вход — новая цепочка")
                .isNotEqualTo(familyOf(first));
        assertThat(authService.refresh(afterLogin).refreshToken()).isNotBlank();
        assertRejected(latest);
    }

    /** Отзывается цепочка одного входа, а не все входы пользователя. */
    @Test
    void refresh_whenUsedTokenPresentedAgain_leavesOtherLoginsOfUserWorking() {
        String stolen = login().refreshToken();
        authService.refresh(stolen);
        String otherDevice = login().refreshToken();

        assertRejected(stolen);

        assertThat(revokedAt(otherDevice)).isNull();
        assertThat(authService.refresh(otherDevice).refreshToken()).isNotBlank();
    }

    /**
     * Повторное предъявление совпало с законным обменом свежего токена той же цепочки: тот уже
     * пометил свой токен и вставил следующий, но ещё не зафиксировал. Отзыв ждёт его и отзывает
     * в том числе выданный им токен. Отзыв одним {@code UPDATE} этот токен пропустил бы: запрос
     * видит данные на момент своего начала, а вставка тогда ещё не была зафиксирована.
     */
    @Test
    void refresh_whenChainRotatesConcurrently_revokesTokenIssuedMeanwhile() throws Exception {
        String first = login().refreshToken();
        String second = authService.refresh(first).refreshToken();
        String issuedMeanwhile = "issued-meanwhile-" + UUID.randomUUID();
        UUID familyId = familyOf(second);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<?> legitimate = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        rotateByHand(second, issuedMeanwhile, familyId);
                        inserted.countDown();
                        await(release);
                    }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            final Future<Boolean> reuse = executor.submit(() -> exchanged(first));
            awaitSomeoneWaitingForLock();
            release.countDown();

            legitimate.get(10, TimeUnit.SECONDS);
            assertThat(reuse.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(revokedAt(issuedMeanwhile)).as("токен, выданный во время отзыва").isNotNull();
        assertThat(revokedAt(second)).isNotNull();
    }

    /** Законный обмен «вручную»: блокирует токен, помечает его и вставляет следующий. */
    private void rotateByHand(String presented, String next, UUID familyId) {
        jdbcTemplate.queryForList("SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
                sha256(presented));
        jdbcTemplate.update("UPDATE refresh_tokens SET used_at = now() WHERE token_hash = ?",
                sha256(presented));
        jdbcTemplate.update("INSERT INTO refresh_tokens (id, family_id, user_id, tenant_id,"
                + " token_hash, expires_at, created_at)"
                + " VALUES (?, ?, ?, ?, ?, now() + interval '1 day', now())",
                UUID.randomUUID(), familyId, user.id(), tenant.id(), sha256(next));
    }

    /**
     * Ждёт, пока какой-нибудь запрос встанет в ожидание блокировки строки. Отдельное
     * соединение: оба соединения тестового пула уже заняты.
     */
    private static void awaitSomeoneWaitingForLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
                PreparedStatement waiting = connection.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity"
                        + " WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet result = waiting.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("Повторное предъявление не встало в ожидание блокировки");
    }

    private boolean exchanged(String rawToken) {
        try {
            authService.refresh(rawToken);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Тест не дождался сигнала");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private AuthResponse login() {
        return authService.authenticate(new LoginRequest(tenant.slug(), EMAIL, PASSWORD));
    }

    private void assertRejected(String rawToken) {
        assertThatThrownBy(() -> authService.refresh(rawToken))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private OffsetDateTime revokedAt(String rawToken) {
        List<OffsetDateTime> found = jdbcTemplate.queryForList(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                OffsetDateTime.class, sha256(rawToken));
        assertThat(found).as("токен есть в базе").hasSize(1);
        return found.get(0);
    }

    private UUID familyOf(String rawToken) {
        return jdbcTemplate.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE token_hash = ?", UUID.class,
                sha256(rawToken));
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

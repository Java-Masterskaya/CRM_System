package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import ru.practicum.crm.security.config.JwtAlgorithm;
import ru.practicum.crm.security.config.JwtProperties;
import ru.practicum.crm.security.domain.RefreshToken;
import ru.practicum.crm.security.repository.RefreshTokenRepository;
import ru.practicum.crm.security.service.RefreshTokenRotation;

/**
 * Обмен refresh-токена без базы (T-027, T-028): репозиторий — заглушка, часы стоят. Блокировки,
 * транзакции и одновременные обмены проверяет {@code RefreshTokenReuseIntegrationTest}.
 */
class RefreshTokenServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final UUID FAMILY_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String RAW_TOKEN = "presented-refresh-token";

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final RefreshTokenServiceImpl service = serviceWith(repository);

    private final Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenServiceImpl.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void rotate_whenTokenIsValid_marksItUsedAndIssuesNextTokenOfSameChain() {
        RefreshToken current = token(NOW_UTC.plusDays(1));
        presented(current);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<RefreshTokenRotation> rotation = service.rotate(RAW_TOKEN);

        assertThat(rotation).hasValueSatisfying(issued -> {
            assertThat(issued.userId()).isEqualTo(USER_ID);
            assertThat(issued.tenantId()).isEqualTo(TENANT_ID);
            assertThat(issued.refreshToken()).isNotBlank().isNotEqualTo(RAW_TOKEN);
        });
        assertThat(current.getUsedAt()).isEqualTo(NOW_UTC);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1)).satisfies(next -> {
            assertThat(next.getFamilyId()).isEqualTo(FAMILY_ID);
            assertThat(next.getUserId()).isEqualTo(USER_ID);
            assertThat(next.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(next.getTokenHash()).isEqualTo(sha256(rotation.orElseThrow()
                    .refreshToken()));
            assertThat(next.getExpiresAt()).isEqualTo(NOW_UTC.plus(REFRESH_TTL));
        });
        verify(repository, never()).revokeFamily(any(), any());
        assertThat(logs.list).isEmpty();
    }

    /**
     * DoD T-028: повторное предъявление использованного токена отклоняется, и отзывается вся
     * цепочка входа. Цепочка сначала блокируется и только потом отзывается.
     */
    @Test
    void rotate_whenTokenAlreadyUsed_revokesWholeChainAndIssuesNothing() {
        presented(usedToken(NOW_UTC.plusDays(1)));
        when(repository.revokeFamily(FAMILY_ID, NOW_UTC)).thenReturn(3);

        assertThat(service.rotate(RAW_TOKEN)).isEmpty();

        InOrder order = inOrder(repository);
        order.verify(repository).lockFamily(FAMILY_ID);
        order.verify(repository).revokeFamily(FAMILY_ID, NOW_UTC);
        verify(repository, never()).save(any());
    }

    /** DoD T-028: событие срабатывания видно в журнале и не содержит значения токена. */
    @Test
    void rotate_whenTokenAlreadyUsed_logsChainWithoutTokenOrItsHash() {
        presented(usedToken(NOW_UTC.plusDays(1)));
        when(repository.revokeFamily(FAMILY_ID, NOW_UTC)).thenReturn(3);

        service.rotate(RAW_TOKEN);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("отозвана",
                    FAMILY_ID.toString(), USER_ID.toString(), TENANT_ID.toString());
            String everything = event.getFormattedMessage()
                    + Arrays.toString(event.getArgumentArray());
            assertThat(everything).doesNotContain(RAW_TOKEN).doesNotContain(sha256(RAW_TOKEN));
        });
    }

    /** Использованный токен — сигнал кражи, даже если он к тому же просрочен. */
    @Test
    void rotate_whenUsedTokenIsAlsoExpired_stillRevokesChain() {
        presented(usedToken(NOW_UTC.minusDays(1)));

        assertThat(service.rotate(RAW_TOKEN)).isEmpty();

        verify(repository).revokeFamily(FAMILY_ID, NOW_UTC);
    }

    /**
     * Цепочку уже отозвали — при прошлом срабатывании или выходе. Повторное предъявление
     * отклоняется без новой тревоги в журнале.
     */
    @Test
    void rotate_whenTokenRevoked_rejectsWithoutRaisingAlarmAgain() {
        RefreshToken revoked = usedToken(NOW_UTC.plusDays(1));
        revoked.revoke(NOW_UTC.minusMinutes(5));
        presented(revoked);

        assertThat(service.rotate(RAW_TOKEN)).isEmpty();

        verify(repository, never()).lockFamily(any());
        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void rotate_whenTokenExpired_rejectsWithoutRevokingChain() {
        presented(token(NOW_UTC));

        assertThat(service.rotate(RAW_TOKEN)).isEmpty();

        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_whenTokenUnknownOrBlank_rejectsWithoutChangingTokens() {
        when(repository.findByTokenHashForUpdate(sha256(RAW_TOKEN))).thenReturn(Optional.empty());

        assertThat(service.rotate(RAW_TOKEN)).as("неизвестный").isEmpty();
        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());

        RefreshTokenRepository untouched = mock(RefreshTokenRepository.class);
        assertThat(serviceWith(untouched).rotate(" ")).as("пустой").isEmpty();
        verifyNoInteractions(untouched);
    }

    private static RefreshTokenServiceImpl serviceWith(RefreshTokenRepository repository) {
        return new RefreshTokenServiceImpl(repository,
                new JwtProperties("secret", JwtAlgorithm.HMAC_SHA256, 32, Duration.ofMinutes(15),
                        REFRESH_TTL),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void presented(RefreshToken token) {
        when(repository.findByTokenHashForUpdate(sha256(RAW_TOKEN))).thenReturn(Optional.of(token));
    }

    private static RefreshToken token(OffsetDateTime expiresAt) {
        return new RefreshToken(UUID.randomUUID(), FAMILY_ID, USER_ID, TENANT_ID,
                sha256(RAW_TOKEN), expiresAt, NOW_UTC.minusDays(2));
    }

    private static RefreshToken usedToken(OffsetDateTime expiresAt) {
        RefreshToken token = token(expiresAt);
        token.markUsed(NOW_UTC.minusHours(1));
        return token;
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

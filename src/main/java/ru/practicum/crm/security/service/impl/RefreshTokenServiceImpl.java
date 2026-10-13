package ru.practicum.crm.security.service.impl;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.security.config.JwtProperties;
import ru.practicum.crm.security.domain.RefreshToken;
import ru.practicum.crm.security.repository.RefreshTokenRepository;
import ru.practicum.crm.security.service.RefreshTokenRotation;
import ru.practicum.crm.security.service.RefreshTokenService;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public String create(UUID userId, UUID tenantId) {
        String refreshToken = generateToken();

        Instant now = clock.instant();
        Instant expiresAt = now.plus(jwtProperties.refreshTtl());

        RefreshToken entity = new RefreshToken(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                tenantId,
                hash(refreshToken),
                toOffsetDateTime(expiresAt),
                toOffsetDateTime(now)
        );

        refreshTokenRepository.save(entity);

        return refreshToken;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Проверки идут в таком порядке. Отозванный токен просто не обменивается: цепочку уже
     * отозвали — при выходе или прошлом срабатывании. Использованный — сигнал кражи, даже если
     * он к тому же просрочен: кто его предъявил, тот держит копию. И только потом срок жизни.
     */
    @Override
    @Transactional
    public Optional<RefreshTokenRotation> rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }

        Optional<RefreshToken> found = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawRefreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();

        Instant nowInstant = clock.instant();
        OffsetDateTime now = toOffsetDateTime(nowInstant);

        if (current.isRevoked()) {
            return Optional.empty();
        }
        if (current.isUsed()) {
            revokeFamilyOnReuse(current, now);
            return Optional.empty();
        }
        if (current.isExpired(now)) {
            return Optional.empty();
        }

        current.markUsed(now);
        refreshTokenRepository.save(current);

        String nextRawToken = generateToken();

        RefreshToken replacement = new RefreshToken(
                UUID.randomUUID(),
                current.getFamilyId(),
                current.getUserId(),
                current.getTenantId(),
                hash(nextRawToken),
                toOffsetDateTime(nowInstant.plus(jwtProperties.refreshTtl())),
                now
        );

        refreshTokenRepository.save(replacement);

        return Optional.of(new RefreshTokenRotation(
                current.getUserId(),
                current.getTenantId(),
                nextRawToken
        ));
    }

    /**
     * Использованный токен предъявлен снова — его копия есть у кого-то ещё (T-028). Отзывается
     * вся цепочка входа, а не только этот токен: неизвестно, у кого из двоих сейчас её свежий
     * токен.
     *
     * <p>В журнал пишутся цепочка, пользователь и арендатор, но не токен и не его хеш.
     */
    private void revokeFamilyOnReuse(RefreshToken reused, OffsetDateTime now) {
        int revoked = revokeFamily(reused.getFamilyId(), now);
        LOG.warn("Повторно предъявлен использованный refresh-токен: цепочка входа {} пользователя"
                        + " {} арендатора {} отозвана, отозвано токенов: {}",
                value("familyId", reused.getFamilyId()), value("userId", reused.getUserId()),
                value("tenantId", reused.getTenantId()), value("revokedTokens", revoked));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Отзывается вся цепочка, а не только предъявленный токен: выход завершает вход целиком,
     * и старые использованные токены этого входа потом не поднимут тревогу о краже (T-028) —
     * они уже отозваны.
     */
    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .ifPresent(token -> revokeFamily(token.getFamilyId(), now()));
    }

    @Override
    @Transactional
    public int revokeAll(UUID userId) {
        refreshTokenRepository.lockAllOfUser(userId);
        int revoked = refreshTokenRepository.revokeAllOfUser(userId, now());
        LOG.info("Отозваны все refresh-токены пользователя {}: {}", value("userId", userId),
                value("revokedTokens", revoked));
        return revoked;
    }

    /**
     * Сначала цепочка блокируется, затем отзывается — почему двумя запросами, сказано в
     * {@link RefreshTokenRepository#lockFamily}.
     */
    private int revokeFamily(UUID familyId, OffsetDateTime now) {
        refreshTokenRepository.lockFamily(familyId);
        return refreshTokenRepository.revokeFamily(familyId, now);
    }

    private OffsetDateTime now() {
        return toOffsetDateTime(clock.instant());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);

        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package ru.practicum.crm.security.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.security.config.JwtProperties;
import ru.practicum.crm.security.domain.RefreshToken;
import ru.practicum.crm.security.repository.RefreshTokenRepository;
import ru.practicum.crm.security.service.RefreshTokenService;

@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

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

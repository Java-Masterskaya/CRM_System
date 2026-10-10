package ru.practicum.crm.security.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import ru.practicum.crm.security.config.JwtProperties;
import ru.practicum.crm.security.service.JwtTokenService;

@Service
@RequiredArgsConstructor
public class JwtTokenServiceImpl implements JwtTokenService {

    private static final String CLAIM_TENANT_ID = "tenant_id";
    private static final String CLAIM_PERMISSIONS = "permissions";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    @Override
    public String createAccessToken(UUID userId, UUID tenantId,
                                    Collection<String> permissions) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId.toString())
                .claim(CLAIM_PERMISSIONS, List.copyOf(permissions))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(properties.algorithm().macAlgorithm()).build(),
                        claims
                ))
                .getTokenValue();
    }
}

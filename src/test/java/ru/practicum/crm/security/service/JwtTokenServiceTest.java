package ru.practicum.crm.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import ru.practicum.crm.security.config.JwtAlgorithm;
import ru.practicum.crm.security.config.JwtProperties;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private Jwt encodedJwt;

    private JwtClaimsSet claims;
    private UUID userId;
    private UUID tenantId;
    private List<String> permissions;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        permissions = List.of("REQUEST_READ", "REQUEST_UPDATE");

        when(encodedJwt.getTokenValue()).thenReturn("access-token");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(encodedJwt);

        JwtTokenService service = new JwtTokenService(
                jwtEncoder,
                new JwtProperties(
                        "test-secret",
                        JwtAlgorithm.HMAC_SHA256,
                        32,
                        ACCESS_TTL,
                        Duration.ofDays(30)
                ),
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC)
        );
        service.createAccessToken(userId, tenantId, permissions);

        ArgumentCaptor<JwtEncoderParameters> parametersCaptor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(parametersCaptor.capture());
        claims = parametersCaptor.getValue().getClaims();
    }

    @Test
    void createAccessToken_setsUserIdAsSubject() {
        assertThat(claims.getSubject())
                .isEqualTo(userId.toString());
    }

    @Test
    void createAccessToken_setsTenantIdClaim() {
        assertThat(claims.getClaimAsString("tenant_id"))
                .isEqualTo(tenantId.toString());
    }

    @Test
    void createAccessToken_setsPermissionsClaim() {
        assertThat(claims.getClaimAsStringList("permissions"))
                .containsExactlyElementsOf(permissions);
    }

    @Test
    void createAccessToken_setsExpirationToIssuedAtPlusAccessTtl() {
        assertThat(claims.getExpiresAt())
                .isEqualTo(claims.getIssuedAt().plus(ACCESS_TTL));
    }
}

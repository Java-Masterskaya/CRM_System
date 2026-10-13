
package ru.practicum.crm.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.config.JwtProperties;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=false")
@Import(JwtAuthenticationIT.SecurityProbeController.class)
class JwtAuthenticationIT extends BaseIntegrationTest {

    private static final String CONTEXT_PATH = "/admin/test/jwt/context";
    private static final String PERMISSION_PATH = "/admin/test/jwt/permission";
    private static final String AUTHORITY = "TENANT_SETTINGS_READ";

    private static final UUID USER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private SecurityProbeController securityProbeController;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @BeforeEach
    void setUp() {
        when(tenantActiveChecker.isActive(TENANT_ID)).thenReturn(true);
        securityProbeController.reset();
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        securityProbeController.reset();
    }

    @Test
    void validAccessToken_populatesUserTenantAndAuthorities() throws Exception {
        String token = accessToken(AUTHORITY);

        mockMvc.perform(get(CONTEXT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.authorities").isArray())
                .andExpect(jsonPath("$.authorities[0]").value(AUTHORITY));

        assertThat(securityProbeController.getInvocationCount()).isEqualTo(1);
        assertThat(tenantContext.getCurrentTenantId()).isNull();
    }

    @Test
    void requestWithoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());

        assertThat(securityProbeController.getInvocationCount()).isZero();
    }

    @Test
    void expiredAccessToken_returnsUnauthorized() throws Exception {
        Instant now = Instant.now();

        String token = signToken(validClaims(
                now.minusSeconds(3600),
                now.minusSeconds(600)
        ));

        expectUnauthorized(token);
    }

    @Test
    void tokenWithTamperedSignature_returnsUnauthorized() throws Exception {
        String token = accessToken(AUTHORITY);
        String tamperedToken = tamperSignature(token);

        expectUnauthorized(tamperedToken);
    }

    @Test
    void tokenSignedWithDifferentKey_returnsUnauthorized() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = validClaims(
                now.minusSeconds(30),
                now.plusSeconds(600)
        );

        byte[] keyBytes = "different-signing-key-for-security-tests-123456"
                .getBytes(StandardCharsets.UTF_8);
        SecretKey foreignKey = new SecretKeySpec(
                keyBytes,
                jwtProperties.algorithm().jcaName()
        );

        OctetSequenceKey jwk = new OctetSequenceKey.Builder(foreignKey)
                .algorithm(jwtProperties.algorithm().jwsAlgorithm())
                .build();

        JwtEncoder foreignEncoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(jwk))
        );

        String token = foreignEncoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(jwtProperties.algorithm().macAlgorithm()).build(),
                        claims
                )
        ).getTokenValue();

        expectUnauthorized(token);
    }

    @Test
    void opaqueRefreshTokenPresentedAsBearer_returnsUnauthorized()
            throws Exception {

        // Refresh-токен в проекте является непрозрачной строкой, а не JWT.
        String refreshToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(UUID.randomUUID().toString()
                        .getBytes(StandardCharsets.UTF_8));

        expectUnauthorized(refreshToken);
    }

    @Test
    void tokenWithoutExpiration_returnsUnauthorized() throws Exception {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(USER_ID.toString())
                .issuedAt(now)
                .claim("tenant_id", TENANT_ID.toString())
                .claim("permissions", List.of(AUTHORITY))
                .build();

        expectUnauthorized(signToken(claims));
    }

    @Test
    void permissionFromToken_grantsMatchingAuthority() throws Exception {
        String token = accessToken(AUTHORITY);

        mockMvc.perform(get(PERMISSION_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("granted"));
    }

    @Test
    void tokenWithoutRequiredPermission_returnsForbidden() throws Exception {
        String token = accessToken("REQUEST_READ_ALL");

        mockMvc.perform(get(PERMISSION_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(securityProbeController.getInvocationCount()).isZero();
    }

    @Test
    void bearerTokenValue_isNotWrittenToLogs() throws Exception {
        String validToken = accessToken(AUTHORITY);
        String invalidToken = tamperSignature(validToken);

        Logger rootLogger = (Logger) LoggerFactory.getLogger(
                Logger.ROOT_LOGGER_NAME
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            mockMvc.perform(get(CONTEXT_PATH)
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + validToken
                            ))
                    .andExpect(status().isOk());

            mockMvc.perform(get(CONTEXT_PATH)
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + invalidToken
                            ))
                    .andExpect(status().isUnauthorized());

            String messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertThat(messages).doesNotContain(validToken, invalidToken);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private String accessToken(String permission) {
        return jwtTokenService.createAccessToken(
                USER_ID,
                TENANT_ID,
                List.of(permission)
        );
    }

    private JwtClaimsSet validClaims(Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .subject(USER_ID.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", TENANT_ID.toString())
                .claim("permissions", List.of(AUTHORITY))
                .build();
    }

    private String signToken(JwtClaimsSet claims) {
        return jwtEncoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader.with(jwtProperties.algorithm().macAlgorithm()).build(),
                        claims
                )
        ).getTokenValue();
    }

    private String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isNotEmpty();

        String signature = parts[2];
        char replacement = signature.charAt(0) == 'a' ? 'b' : 'a';

        return parts[0] + "." + parts[1] + "."
                + replacement + signature.substring(1);
    }

    private void expectUnauthorized(String token) throws Exception {
        mockMvc.perform(get(CONTEXT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        assertThat(securityProbeController.getInvocationCount()).isZero();
    }

    @RestController
    public static class SecurityProbeController {

        private final TenantContext tenantContext;
        private int invocationCount;

        @SuppressFBWarnings(
                value = "EI_EXPOSE_REP2",
                justification = "TenantContext — общий компонент под управлением Spring; "
                                + "сохранение ссылки на него является намеренным."
        )
        public SecurityProbeController(TenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        @GetMapping(CONTEXT_PATH)
        public ProbeResponse context(Authentication authentication) {
            invocationCount++;

            UUID userId = UUID.fromString(authentication.getName());

            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .sorted()
                    .toList();

            return new ProbeResponse(
                    userId,
                    tenantContext.getCurrentTenantId(),
                    authorities
            );
        }

        @GetMapping(PERMISSION_PATH)
        @PreAuthorize("hasAuthority('TENANT_SETTINGS_READ')")
        public String permissionProtected() {
            invocationCount++;
            return "granted";
        }

        int getInvocationCount() {
            return invocationCount;
        }

        void reset() {
            invocationCount = 0;
        }
    }

    public record ProbeResponse(
            UUID userId,
            UUID tenantId,
            List<String> authorities
    ) {
        public ProbeResponse {
            authorities = List.copyOf(authorities);
        }

        @Override
        public List<String> authorities() {
            return Collections.unmodifiableList(authorities);
        }
    }
}

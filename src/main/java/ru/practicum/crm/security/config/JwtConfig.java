package ru.practicum.crm.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class JwtConfig {
    private final JwtProperties properties;

    @Bean
    public SecretKey jwtSecretKey() {
        final byte[] keyBytes = decodeSecret();

        if (keyBytes.length < properties.minSecretLengthBytes()) {
            throw new IllegalStateException(
                    "Свойство security.jwt.secret должно быть не менее "
                    + properties.minSecretLengthBytes()
                    + " байт"
            );
        }

        return new SecretKeySpec(keyBytes, properties.algorithm().jcaName());
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(jwtSecretKey)
                .algorithm(properties.algorithm().jwsAlgorithm())
                .build();

        return new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(jwk))
        );
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(properties.algorithm().macAlgorithm())
                .build();

        OAuth2TokenValidator<Jwt> claimsValidator = jwt -> {
            String subject = jwt.getSubject();
            Object tenantClaim = jwt.getClaims().get("tenant_id");
            Object permissionsClaim = jwt.getClaims().get("permissions");

            if (subject == null
                    || subject.isBlank()
                    || jwt.getExpiresAt() == null
                    || !(tenantClaim instanceof String tenantId)
                    || tenantId.isBlank()
                    || !(permissionsClaim instanceof Collection<?> permissions)
                    || permissions.stream().anyMatch(
                            permission -> !(permission instanceof String))) {
                return invalidClaims();
            }

            try {
                UUID.fromString(subject);
                UUID.fromString(tenantId);
            } catch (IllegalArgumentException exception) {
                return invalidClaims();
            }

            return OAuth2TokenValidatorResult.success();
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                claimsValidator
        ));

        return decoder;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    private byte[] decodeSecret() {
        try {
            return Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Свойство crm.security.jwt.secret должно содержать значение в Base64",
                    exception
            );
        }
    }

    private OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token")
        );
    }
}

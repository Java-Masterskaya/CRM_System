package ru.practicum.crm.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
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
        return NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(properties.algorithm().macAlgorithm())
                .build();
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

}

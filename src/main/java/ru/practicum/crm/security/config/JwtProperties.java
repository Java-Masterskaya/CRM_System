package ru.practicum.crm.security.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "crm.security.jwt")
public record JwtProperties(
        @NotBlank
        String secret,
        @NotNull
        JwtAlgorithm algorithm,
        @Positive
        int minSecretLengthBytes,
        @NotNull
        @DurationMin(seconds = 1)
        Duration accessTtl,
        @NotNull
        @DurationMin(seconds = 1)
        Duration refreshTtl
) {
}

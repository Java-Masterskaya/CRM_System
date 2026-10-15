package ru.practicum.crm.security.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "crm.security.login-rate-limit")
public record LoginRateLimitProperties(

        @Positive
        int maxAttempts,

        @NotNull
        @DurationMin(seconds = 1)
        Duration window,

        @NotNull
        @DurationMin(seconds = 1)
        Duration blockDuration
) {
}

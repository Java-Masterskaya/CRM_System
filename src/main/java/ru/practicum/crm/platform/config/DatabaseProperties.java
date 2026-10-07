package ru.practicum.crm.platform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.database")
@Validated
public record DatabaseProperties(
        @NotBlank
        String url,

        @NotBlank
        String username,

        @NotBlank
        String password
) {
}

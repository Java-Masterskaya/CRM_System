package ru.practicum.crm.security.system;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "crm.system")
public record SystemSecretProperties(
        @NotBlank
        String secret
) {
}

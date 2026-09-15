package ru.practicum.crm.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.mail")
@Validated
public record MailProperties(
        @NotBlank String host,

        @Min(1) @Max(65535)
        int port,

        @NotNull Boolean auth,

        String username,

        String password
) {

    @AssertTrue(message = "username and password are required when SMTP authentication is enabled")
    public boolean isCredentialsValid() {
        if (!auth) {
            return true;
        }

        return username != null
                && !username.isBlank()
                && password != null
                && !password.isBlank();
    }
}

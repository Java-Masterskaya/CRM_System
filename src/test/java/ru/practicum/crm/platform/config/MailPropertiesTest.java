package ru.practicum.crm.platform.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.platform.config.MailProperties;

import static org.assertj.core.api.Assertions.assertThat;

class MailPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldAcceptMailConfigWithoutCredentialsWhenAuthDisabled() {
        var properties = new MailProperties(
                "localhost",
                1025,
                false,
                null,
                null
        );

        var violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectMailConfigWithoutCredentialsWhenAuthEnabled() {
        var properties = new MailProperties(
                "smtp.example.com",
                587,
                true,
                null,
                null
        );

        var violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getMessage()
                        .contains("username and password are required"));
    }

    @Test
    void shouldAcceptMailConfigWithCredentialsWhenAuthEnabled() {
        var properties = new MailProperties(
                "smtp.example.com",
                587,
                true,
                "user@example.com",
                "secret"
        );

        var violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectInvalidPort() {
        var properties = new MailProperties(
                "localhost",
                70000,
                false,
                null,
                null
        );

        var violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("port"));
    }
}

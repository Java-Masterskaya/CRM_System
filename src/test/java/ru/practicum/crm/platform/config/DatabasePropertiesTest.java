package ru.practicum.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabasePropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldAcceptValidDatabaseConfig() {
        var properties = new DatabaseProperties(
                "jdbc:postgresql://localhost:5432/crm_db",
                "crm_user",
                "crm_password"
        );

        var violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectMissingDatabaseUrl() {
        var properties = new DatabaseProperties(
                "",
                "crm_user",
                "crm_password"
        );

        var violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("url"));
    }

    @Test
    void shouldRejectMissingDatabaseUsername() {
        var properties = new DatabaseProperties(
                "jdbc:postgresql://localhost:5432/crm_db",
                "",
                "crm_password"
        );

        var violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("username"));
    }

    @Test
    void shouldRejectMissingDatabasePassword() {
        var properties = new DatabaseProperties(
                "jdbc:postgresql://localhost:5432/crm_db",
                "crm_user",
                ""
        );

        var violations = validator.validate(properties);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }
}

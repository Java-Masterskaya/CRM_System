package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.security.config.PasswordPolicyProperties;

class PasswordPolicyImplTest {

    private PasswordPolicyImpl passwordPolicy;

    @BeforeEach
    void setUp() {
        passwordPolicy = new PasswordPolicyImpl(
                new PasswordPolicyProperties(
                        8,
                        72,
                        12,
                        true,
                        true,
                        true,
                        true
                )
        );
    }

    @Test
    void validPassword_hasNoViolations() {
        assertThat(passwordPolicy.violations("GoodPass1!"))
                .isEmpty();
    }

    @Test
    void weakPassword_returnsAllViolations() {
        assertThat(passwordPolicy.violations("weak"))
                .isNotEmpty();
    }

    @Test
    void shouldAcceptPasswordOfExactly72Bytes() {
        String password = "a".repeat(72);

        assertThat(passwordPolicy.violations(password))
                .noneMatch(message -> message.contains("байт"));
    }

    @Test
    void shouldRejectPasswordLongerThan72Bytes() {
        String password = "a".repeat(73);

        assertThat(passwordPolicy.violations(password))
                .anyMatch(message -> message.contains("72 байт"));
    }

    @Test
    void shouldRejectPasswordWith72CodePointsButMoreThan72Bytes() {
        String password = "a".repeat(71) + "€";

        assertThat(password.codePointCount(0, password.length()))
                .isEqualTo(72);

        assertThat(password.getBytes(StandardCharsets.UTF_8))
                .hasSize(74);

        assertThat(passwordPolicy.violations(password))
                .anyMatch(message -> message.contains("байт"));
    }
}

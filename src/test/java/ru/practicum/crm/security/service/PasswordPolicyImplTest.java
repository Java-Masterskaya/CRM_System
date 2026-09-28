package ru.practicum.crm.security.service;

import static org.assertj.core.api.Assertions.assertThat;

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
                        64,
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
}

package ru.practicum.crm.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crm.security.password")
public record PasswordPolicyProperties(
        int minLength,
        int maxLength,
        boolean requireUppercase,
        boolean requireLowercase,
        boolean requireDigit,
        boolean requireSpecial
) {
}

package ru.practicum.crm.security.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank
        String tenantSlug,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String password
) {
}

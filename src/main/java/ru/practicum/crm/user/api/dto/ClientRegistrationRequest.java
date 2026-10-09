package ru.practicum.crm.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ClientRegistrationRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
        String tenantSlug,
        @NotBlank
        @Email
        @Size(max = 255)
        String email,
        @NotBlank
        String password,
        @NotBlank
        @Size(max = 255)
        String name
) {
}

package ru.practicum.crm.system.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SystemTenantOnboardingRequest(

        @NotBlank
        String name,

        @NotBlank
        String slug,

        @NotBlank
        @Email
        String adminEmail,

        @NotBlank
        String adminPassword
) {
}

package ru.practicum.crm.system.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SystemTenantOnboardingRequest(

        @NotBlank
        String name,

        @NotBlank
        @Pattern(
                regexp = "^[a-z0-9-]{2,63}$",
                message = "slug должен содержать 2–63 символа: строчные латинские буквы, "
                        + "цифры и дефис"
        )
        String slug,

        @NotBlank
        @Email
        String adminEmail,

        @NotBlank
        String adminPassword
) {
}

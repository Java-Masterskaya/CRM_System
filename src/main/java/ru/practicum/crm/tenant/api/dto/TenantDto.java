package ru.practicum.crm.tenant.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TenantDto(
        @NotBlank
        String name,
        @NotBlank
        @Pattern(
                regexp = "^[a-z0-9-]{2,63}$",
                message = "slug должен содержать только строчные "
                          + "латинские буквы, цифры и дефисы, длина от 2 до 63 символов"
        )
        String slug,
        Boolean active
) {
}

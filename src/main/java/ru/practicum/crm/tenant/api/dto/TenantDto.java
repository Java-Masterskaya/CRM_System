package ru.practicum.crm.tenant.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TenantDto(
        @NotBlank
        String name,
        @NotBlank
        String slug,
        Boolean active
) {
}

package ru.practicum.crm.tenant.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TenantDto(
        @NotBlank
        String name,
        Boolean active,
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
        String slug
) {
    public TenantDto(String name, Boolean active) {
        this(name, active, toSlug(name));
    }

    private static String toSlug(String value) {
        String slugValue = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slugValue.isBlank() ? "tenant" : slugValue;
    }
}

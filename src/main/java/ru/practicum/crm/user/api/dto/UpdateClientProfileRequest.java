package ru.practicum.crm.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateClientProfileRequest(
        @NotBlank
        @Size(max = 255)
        String name,
        @Size(max = 40)
        @Pattern(regexp = "\\+?[0-9 ()-]{7,40}")
        String phone
) {
}

package ru.practicum.crm.security.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(

        @NotBlank
        String refreshToken
) {
}

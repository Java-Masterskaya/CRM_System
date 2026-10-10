package ru.practicum.crm.security.api.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken
) {
}

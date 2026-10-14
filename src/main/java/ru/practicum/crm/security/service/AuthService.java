package ru.practicum.crm.security.service;

import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;

public interface AuthService {

    AuthResponse authenticate(LoginRequest request);

    AuthResponse authenticate(LoginRequest request, String sourceIp);

    AuthResponse refresh(String refreshToken);

    /**
     * Выход: refresh-токены этого входа больше не обмениваются (T-029). Повторный выход
     * безопасен.
     */
    void logout(String refreshToken);
}

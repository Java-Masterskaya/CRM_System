package ru.practicum.crm.security.service;

import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;

public interface AuthService {
    AuthResponse authenticate(LoginRequest request);
}

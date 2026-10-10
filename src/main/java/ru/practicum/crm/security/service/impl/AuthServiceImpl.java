package ru.practicum.crm.security.service.impl;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantAuthDto;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final TenantService tenantService;
    private final UserService userService;
    private final UserPermissionService userPermissionService;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    @Override
    public AuthResponse authenticate(LoginRequest request) {
        TenantAuthDto tenant = tenantService.findActiveBySlug(request.tenantSlug());

        AuthenticatedUserDto user = userService.authenticate(tenant.id(), request.email(),
                request.password());

        Set<String> permissions = userPermissionService.getUserPermissionCodes(user.id());

        String accessToken = jwtTokenService.createAccessToken(user.id(), user.tenantId(),
                permissions);

        String refreshToken = refreshTokenService.create(
                user.id(),
                tenant.id()
        );

        return new AuthResponse(
                accessToken,
                refreshToken
        );
    }
}

package ru.practicum.crm.security.service.impl;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.LoginRateLimiter;
import ru.practicum.crm.security.service.RefreshTokenRotation;
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
    private final LoginRateLimiter loginRateLimiter;

    @Override
    public AuthResponse authenticate(LoginRequest request) {
        return authenticate(request, "unknown");
    }

    @Override
    public AuthResponse authenticate(LoginRequest request, String sourceIp) {
        loginRateLimiter.checkAllowed(
                request.tenantSlug(),
                request.email(),
                sourceIp
        );

        try {
            TenantAuthDto tenant =
                    tenantService.findActiveBySlug(request.tenantSlug());

            AuthenticatedUserDto user = userService.authenticate(
                    tenant.id(),
                    request.email(),
                    request.password()
            );

            Set<String> permissions =
                    userPermissionService.getUserPermissionCodes(user.id());

            String accessToken = jwtTokenService.createAccessToken(
                    user.id(),
                    user.tenantId(),
                    permissions
            );

            String refreshToken = refreshTokenService.create(
                    user.id(),
                    tenant.id()
            );

            loginRateLimiter.recordSuccess(
                    request.tenantSlug(),
                    request.email(),
                    sourceIp
            );

            return new AuthResponse(accessToken, refreshToken);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) {
                loginRateLimiter.recordFailure(
                        request.tenantSlug(),
                        request.email(),
                        sourceIp
                );
            }

            throw exception;
        }
    }

    @Override
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenRotation rotation =
                refreshTokenService.rotate(rawRefreshToken);

        Set<String> permissions =
                userPermissionService.getUserPermissionCodes(rotation.userId());

        String accessToken = jwtTokenService.createAccessToken(
                rotation.userId(),
                rotation.tenantId(),
                permissions
        );

        return new AuthResponse(
                accessToken,
                rotation.refreshToken()
        );
    }
}

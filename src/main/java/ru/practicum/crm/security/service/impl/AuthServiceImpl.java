package ru.practicum.crm.security.service.impl;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
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

    /**
     * Обмен refresh-токена на новую пару (T-027).
     *
     * <p>Метод намеренно не транзакционный. Обмен — а при повторном использовании токена отзыв
     * всей цепочки входа (T-028) — фиксируется в транзакции {@link RefreshTokenService#rotate},
     * и отказ бросается уже после неё. Внутри общей транзакции исключение откатило бы отзыв
     * цепочки, и украденный токен остался бы рабочим.
     */
    @Override
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenRotation rotation = refreshTokenService.rotate(rawRefreshToken)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "Refresh-токен недействителен."));

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

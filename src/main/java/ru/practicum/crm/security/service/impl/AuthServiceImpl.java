package ru.practicum.crm.security.service.impl;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.LoginRateLimiter;
import ru.practicum.crm.security.service.RefreshTokenRotation;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantAuthDto;
import ru.practicum.crm.user.api.UserAccessTokenState;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.UserTokenState;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final TenantService tenantService;
    private final UserService userService;
    private final UserPermissionService userPermissionService;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final LoginRateLimiter loginRateLimiter;
    private final TenantActiveChecker tenantActiveChecker;
    private final TransactionOperations transactions;
    private final UserAccessTokenState tokenState;

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
            AuthResponse issued = Objects.requireNonNull(
                    transactions.execute(status -> issueOnLogin(request)));

            loginRateLimiter.recordSuccess(
                    request.tenantSlug(),
                    request.email(),
                    sourceIp
            );

            return issued;
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

    private AuthResponse issueOnLogin(LoginRequest request) {
        TenantAuthDto tenant = tenantService.findActiveBySlug(request.tenantSlug());
        AuthenticatedUserDto user = userService.authenticate(tenant.id(), request.email(),
                request.password());
        UserTokenState state = tokenState.lockForIssuance(tenant.id(), user.id())
                .filter(UserTokenState::active)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.getDefaultDetail()));
        Set<String> permissions = userPermissionService.getUserPermissionCodes(user.id());
        String access = jwtTokenService.createAccessToken(user.id(), tenant.id(), permissions,
                state.version());
        return new AuthResponse(access, refreshTokenService.create(user.id(), tenant.id()));
    }

    @Override
    public AuthResponse refresh(String rawRefreshToken) {
        Optional<AuthResponse> issued = transactions.execute(status ->
                refreshInTransaction(rawRefreshToken));

        return Objects.requireNonNullElse(
                        issued,
                        Optional.<AuthResponse>empty()
                )
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNAUTHENTICATED,
                        "Refresh-токен недействителен."
                ));
    }

    private Optional<AuthResponse> refreshInTransaction(String rawRefreshToken) {
        return refreshTokenService.findSubject(rawRefreshToken).flatMap(subject -> {
            if (!tenantActiveChecker.isActive(subject.tenantId())) {
                return Optional.empty();
            }
            // Общий порядок блокировок: сначала пользователь, затем его токены.
            return tokenState.lockForIssuance(subject.tenantId(), subject.userId())
                    .filter(UserTokenState::active)
                    .flatMap(state -> refreshTokenService.rotate(rawRefreshToken)
                            .map(rotation -> issueFor(rotation, state.version())));
        });
    }

    private AuthResponse issueFor(RefreshTokenRotation rotation, long accessVersion) {
        Set<String> permissions =
                userPermissionService.getUserPermissionCodes(rotation.userId());

        String accessToken = jwtTokenService.createAccessToken(
                rotation.userId(),
                rotation.tenantId(),
                permissions,
                accessVersion
        );

        return new AuthResponse(
                accessToken,
                rotation.refreshToken()
        );
    }

    @Override
    public void logout(String rawRefreshToken) {
        refreshTokenService.logout(rawRefreshToken);
    }
}

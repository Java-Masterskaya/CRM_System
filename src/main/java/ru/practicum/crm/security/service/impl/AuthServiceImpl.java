package ru.practicum.crm.security.service.impl;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.RefreshTokenRotation;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
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
    private final TenantActiveChecker tenantActiveChecker;
    private final TransactionOperations transactions;

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
     * <p>Ротация, проверка пользователя и арендатора, сбор прав и выпуск access-токена — одна
     * транзакция. Если выпуск сорвётся, откатится и ротация: старый токен останется рабочим, и
     * повтор не будет принят за кражу (T-028). Пользователь, заблокированный после входа, и
     * неактивный арендатор получают тот же отказ, что при входе, а ротация тоже откатывается.
     *
     * <p>Отказ бросается уже после транзакции, а внутри неё — пустой результат: при повторном
     * использовании токена отзыв цепочки входа должен зафиксироваться, а исключение откатило бы
     * его, и украденный токен остался бы рабочим.
     */
    @Override
    public AuthResponse refresh(String rawRefreshToken) {
        Optional<AuthResponse> issued = transactions.execute(status ->
                refreshTokenService.rotate(rawRefreshToken)
                        .flatMap(rotation -> issueFor(rotation, status)));
        return Objects.requireNonNullElse(issued, Optional.<AuthResponse>empty())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "Refresh-токен недействителен."));
    }

    /**
     * Пара для обменянного токена — если пользователь и арендатор по-прежнему могут работать.
     * Если нет, ротация откатывается: обмена не было, токен остаётся таким же, как до запроса.
     */
    private Optional<AuthResponse> issueFor(RefreshTokenRotation rotation,
            TransactionStatus status) {
        if (!tenantActiveChecker.isActive(rotation.tenantId())
                || !userService.canLogIn(rotation.tenantId(), rotation.userId())) {
            status.setRollbackOnly();
            return Optional.empty();
        }

        Set<String> permissions =
                userPermissionService.getUserPermissionCodes(rotation.userId());

        String accessToken = jwtTokenService.createAccessToken(
                rotation.userId(),
                rotation.tenantId(),
                permissions
        );

        return Optional.of(new AuthResponse(
                accessToken,
                rotation.refreshToken()
        ));
    }

    @Override
    public void logout(String rawRefreshToken) {
        refreshTokenService.logout(rawRefreshToken);
    }
}

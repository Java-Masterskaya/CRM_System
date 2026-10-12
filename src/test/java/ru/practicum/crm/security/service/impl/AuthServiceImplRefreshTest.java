package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.RefreshTokenRotation;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;

/**
 * Обмен refresh-токена в сервисе входа без базы: обмен токена — заглушка. Что отказ не
 * откатывает отзыв цепочки, проверяет {@code RefreshTokenReuseIntegrationTest}.
 */
class AuthServiceImplRefreshTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final JwtTokenService jwtTokens = mock(JwtTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuthServiceImpl service = new AuthServiceImpl(mock(TenantService.class),
            mock(UserService.class), permissions, jwtTokens, refreshTokens);

    @Test
    void refresh_whenTokenExchanged_returnsAccessTokenWithCurrentPermissionsAndNewRefresh() {
        when(refreshTokens.rotate("old")).thenReturn(
                Optional.of(new RefreshTokenRotation(USER_ID, TENANT_ID, "new")));
        when(permissions.getUserPermissionCodes(USER_ID)).thenReturn(Set.of("REQUEST_READ"));
        when(jwtTokens.createAccessToken(USER_ID, TENANT_ID, Set.of("REQUEST_READ")))
                .thenReturn("access");

        assertThat(service.refresh("old")).isEqualTo(new AuthResponse("access", "new"));
    }

    @Test
    void refresh_whenTokenNotExchanged_isUnauthenticatedWithoutIssuingAccessToken() {
        when(refreshTokens.rotate("used")).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(() -> service.refresh("used"),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(permissions, jwtTokens);
    }
}

package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.AuthResponse;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.security.service.LoginRateLimiter;
import ru.practicum.crm.security.service.RefreshTokenRotation;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.api.UserService;

/**
 * Обмен refresh-токена в сервисе входа без базы: обмен токена — заглушка, транзакция —
 * подмена, которая только запоминает, просили ли её откатить. Что откат и фиксация происходят
 * на самом деле, проверяют интеграционные тесты.
 */
class AuthServiceImplRefreshTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    private final UserService users = mock(UserService.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final JwtTokenService jwtTokens = mock(JwtTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final LoginRateLimiter loginRateLimiter =
            mock(LoginRateLimiter.class);
    private final TenantActiveChecker tenants = mock(TenantActiveChecker.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final AuthServiceImpl service = new AuthServiceImpl(mock(TenantService.class), users,
            permissions, jwtTokens, refreshTokens, loginRateLimiter,
            tenants, inTransaction(transaction));

    @BeforeEach
    void userAndTenantCanWork() {
        when(tenants.isActive(TENANT_ID)).thenReturn(true);
        when(users.canLogIn(TENANT_ID, USER_ID)).thenReturn(true);
    }

    @Test
    void refresh_whenTokenExchanged_returnsAccessTokenWithCurrentPermissionsAndNewRefresh() {
        exchanged("old");
        when(permissions.getUserPermissionCodes(USER_ID)).thenReturn(Set.of("REQUEST_READ"));
        when(jwtTokens.createAccessToken(USER_ID, TENANT_ID, Set.of("REQUEST_READ")))
                .thenReturn("access");

        assertThat(service.refresh("old")).isEqualTo(new AuthResponse("access", "new"));
        assertThat(transaction.isRollbackOnly()).isFalse();
    }

    /**
     * Токен не обменян — в том числе потому, что его украли и цепочку отозвали. Отказ
     * приходит без отката транзакции: отзыв цепочки должен зафиксироваться.
     */
    @Test
    void refresh_whenTokenNotExchanged_isUnauthenticatedAndKeepsTransaction() {
        when(refreshTokens.rotate("used")).thenReturn(Optional.empty());

        assertUnauthenticated("used");

        assertThat(transaction.isRollbackOnly()).isFalse();
        verifyNoInteractions(permissions, jwtTokens);
    }

    /**
     * Заблокирован после входа: тот же отказ, что при входе, а ротация откатывается.
     */
    @Test
    void refresh_whenUserBlockedAfterLogin_isUnauthenticatedAndRollsRotationBack() {
        exchanged("old");
        when(users.canLogIn(TENANT_ID, USER_ID)).thenReturn(false);

        assertUnauthenticated("old");

        assertThat(transaction.isRollbackOnly()).isTrue();
        verifyNoInteractions(permissions, jwtTokens);
    }

    @Test
    void refresh_whenTenantDeactivatedAfterLogin_isUnauthenticatedAndRollsRotationBack() {
        exchanged("old");
        when(tenants.isActive(TENANT_ID)).thenReturn(false);

        assertUnauthenticated("old");

        assertThat(transaction.isRollbackOnly()).isTrue();
        verifyNoInteractions(permissions, jwtTokens);
    }

    /**
     * Выпуск access-токена сорвался: ошибка выходит из транзакции, и транзакция ротации
     * откатывается — старый токен остаётся рабочим.
     */
    @Test
    void refresh_whenAccessTokenCannotBeIssued_failsInsideTransaction() {
        exchanged("old");
        when(jwtTokens.createAccessToken(any(), any(), any()))
                .thenThrow(new IllegalStateException("ключ недоступен"));

        assertThatThrownBy(() -> service.refresh("old"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ключ недоступен");
        assertThat(transaction.isCompleted()).as("ошибка вышла изнутри транзакции").isFalse();
    }

    private void exchanged(String rawToken) {
        when(refreshTokens.rotate(rawToken)).thenReturn(
                Optional.of(new RefreshTokenRotation(USER_ID, TENANT_ID, "new")));
    }

    private void assertUnauthenticated(String rawToken) {
        ApiException thrown = catchThrowableOfType(() -> service.refresh(rawToken),
                ApiException.class);
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /**
     * Подмена транзакции: выполняет действие сразу и отмечает завершение только при обычном
     * выходе — как настоящая, которая при исключении откатывается, а не фиксируется.
     */
    private static TransactionOperations inTransaction(SimpleTransactionStatus status) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(status);
                status.setCompleted();
                return result;
            }
        };
    }
}

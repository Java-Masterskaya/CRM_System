package ru.practicum.crm.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserRefreshTokenRevoker;
import ru.practicum.testsupport.AuthTestFixture;

/** Транзакционный контракт публичного порта отзыва из user.api в security. */
@Import(AuthTestFixture.class)
class UserRefreshTokenRevokerIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String OWNER = "owner@example.com";
    private static final String COLLEAGUE = "colleague@example.com";

    @Autowired
    private UserRefreshTokenRevoker revoker;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture fixture;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private TenantContext tenantContext;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestTenant otherTenant;
    private AuthTestFixture.TestUser owner;

    @BeforeEach
    void createUsers() {
        tenantContext.clear();
        tenant = fixture.createTenant("revoker-a");
        otherTenant = fixture.createTenant("revoker-b");
        owner = fixture.createUser(tenant, OWNER, PASSWORD);
        fixture.createUser(tenant, COLLEAGUE, PASSWORD);
        fixture.createUser(otherTenant, OWNER, PASSWORD);
    }

    @AfterEach
    void clearTenant() {
        tenantContext.clear();
    }

    @Test
    void givenNoTransaction_whenRevoking_thenRejectsAndKeepsSession() {
        final String token = login(tenant, OWNER);

        assertThatThrownBy(() -> revoker.revokeAll(owner.id()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(authService.refresh(token).refreshToken()).isNotBlank();
    }

    @Test
    void givenCommittedTransaction_whenRevoking_thenEndsOnlySelectedUsersSessions() {
        final String phone = login(tenant, OWNER);
        final String laptop = login(tenant, OWNER);
        final String colleague = login(tenant, COLLEAGUE);
        final String foreign = login(otherTenant, OWNER);

        tenantContext.setTenantId(tenant.id());
        try {
            transactions.executeWithoutResult(status -> revoker.revokeAll(owner.id()));
        } finally {
            tenantContext.clear();
        }

        assertRejected(phone);
        assertRejected(laptop);
        assertThat(authService.refresh(colleague).refreshToken()).isNotBlank();
        assertThat(authService.refresh(foreign).refreshToken()).isNotBlank();
    }

    @Test
    void givenRolledBackTransaction_whenRevoking_thenKeepsAllSessions() {
        final String phone = login(tenant, OWNER);
        final String laptop = login(tenant, OWNER);

        tenantContext.setTenantId(tenant.id());
        try {
            transactions.executeWithoutResult(status -> {
                revoker.revokeAll(owner.id());
                status.setRollbackOnly();
            });
        } finally {
            tenantContext.clear();
        }

        assertThat(authService.refresh(phone).refreshToken()).isNotBlank();
        assertThat(authService.refresh(laptop).refreshToken()).isNotBlank();
    }

    @Test
    void givenFailureAfterRevocation_whenTransactionFails_thenKeepsSession() {
        final String token = login(tenant, OWNER);

        tenantContext.setTenantId(tenant.id());
        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                revoker.revokeAll(owner.id());
                throw new IllegalStateException("Проверка отката");
            })).isInstanceOf(IllegalStateException.class).hasMessage("Проверка отката");
        } finally {
            tenantContext.clear();
        }

        assertThat(authService.refresh(token).refreshToken()).isNotBlank();
    }

    private String login(AuthTestFixture.TestTenant accountTenant, String email) {
        return authService.authenticate(new LoginRequest(accountTenant.slug(), email, PASSWORD))
                .refreshToken();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> authService.refresh(token))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}

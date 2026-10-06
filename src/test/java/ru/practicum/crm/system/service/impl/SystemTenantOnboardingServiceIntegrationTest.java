package ru.practicum.crm.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingRequest;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingResponse;
import ru.practicum.crm.system.service.SystemTenantOnboardingService;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;

class SystemTenantOnboardingServiceIntegrationTest
        extends BaseIntegrationTest {

    private static final String TENANT_NAME = "Acme";
    private static final String TENANT_SLUG = "acme";
    private static final String ADMIN_EMAIL = "admin@acme.test";
    private static final String ADMIN_PASSWORD = "StrongPassword1!";

    @Autowired
    private SystemTenantOnboardingService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private TenantAccessSeeder tenantAccessSeeder;

    @Test
    void onboard_whenRequestIsValid_createsTenantSettingsAdminAndRole() {

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        TENANT_NAME,
                        TENANT_SLUG,
                        ADMIN_EMAIL,
                        ADMIN_PASSWORD
                );

        SystemTenantOnboardingResponse response =
                service.onboard(request);

        assertThat(response.tenantId()).isNotNull();
        assertThat(response.name()).isEqualTo(TENANT_NAME);
        assertThat(response.slug()).isEqualTo(TENANT_SLUG);
        assertThat(response.active()).isTrue();

        Integer tenantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE id = ? AND slug = ?",
                Integer.class,
                response.tenantId(),
                TENANT_SLUG
        );

        assertThat(tenantCount).isEqualTo(1);

        Integer settingsCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM tenant_settings
                WHERE tenant_id = ?
                """,
                Integer.class,
                response.tenantId()
        );

        assertThat(settingsCount).isEqualTo(1);

        Integer adminRoleCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM roles
                WHERE tenant_id = ?
                  AND code = 'ADMIN'
                """,
                Integer.class,
                response.tenantId()
        );

        assertThat(adminRoleCount).isEqualTo(1);

        Integer adminUserCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM users
                WHERE tenant_id = ?
                  AND email = ?
                """,
                Integer.class,
                response.tenantId(),
                ADMIN_EMAIL
        );

        assertThat(adminUserCount).isEqualTo(1);

        String passwordHash = jdbcTemplate.queryForObject(
                """
                SELECT password_hash
                FROM users
                WHERE tenant_id = ?
                  AND email = ?
                """,
                String.class,
                response.tenantId(),
                ADMIN_EMAIL
        );

        assertThat(passwordHash)
                .isNotBlank()
                .isNotEqualTo(ADMIN_PASSWORD);

        Integer userRoleCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM user_roles ur
                JOIN users u
                    ON u.id = ur.user_id
                   AND u.tenant_id = ur.tenant_id
                JOIN roles r
                    ON r.id = ur.role_id
                   AND r.tenant_id = ur.tenant_id
                WHERE ur.tenant_id = ?
                  AND u.email = ?
                  AND r.code = 'ADMIN'
                """,
                Integer.class,
                response.tenantId(),
                ADMIN_EMAIL
        );

        assertThat(userRoleCount).isEqualTo(1);
    }

    @Test
    void onboard_whenSlugAlreadyExists_rejectsRequest() {
        UUID existingTenantId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (
                    id,
                    name,
                    active,
                    slug,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, now(), now())
                """,
                existingTenantId,
                "Existing Tenant",
                true,
                TENANT_SLUG
        );

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "Another Tenant",
                        TENANT_SLUG,
                        "other@acme.test",
                        ADMIN_PASSWORD
                );

        assertThatThrownBy(() -> service.onboard(request))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;

                    assertThat(apiException.getErrorCode())
                            .isEqualTo(ErrorCode.ALREADY_EXISTS);
                });

        Integer userCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM users
                WHERE tenant_id = ?
                  AND email = ?
                """,
                Integer.class,
                existingTenantId,
                "other@acme.test"
        );

        assertThat(userCount).isEqualTo(0);

        Integer tenantCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM tenants
                WHERE id = ?
                  AND slug = ?
                """,
                Integer.class,
                existingTenantId,
                TENANT_SLUG
        );

        assertThat(tenantCount).isEqualTo(1);
    }

    @Test
    void onboard_whenAdminPasswordIsInvalid_rollsBackEntireTransaction() {
        String slug = "rollback-tenant";
        String email = "admin@rollback.test";

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "Rollback Tenant",
                        slug,
                        email,
                        "weak"
                );

        assertThatThrownBy(() -> service.onboard(request))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;

                    assertThat(apiException.getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED);
                });

        Integer tenantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?",
                Integer.class,
                slug
        );

        assertThat(tenantCount).isEqualTo(0);
    }

    @Test
    void onboard_whenTenantAccessSeedingFails_rollsBackEntireTransaction() {
        String slug = "rollback-seeding";
        String email = "admin@rollback-seeding.test";

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "Rollback Seeding Tenant",
                        slug,
                        email,
                        ADMIN_PASSWORD
                );

        doThrow(new IllegalStateException("forced failure"))
                .when(tenantAccessSeeder)
                .seedDefaults(any(UUID.class));

        assertThatThrownBy(() -> service.onboard(request))
                .isInstanceOf(IllegalStateException.class);

        Integer tenantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?",
                Integer.class,
                slug
        );

        assertThat(tenantCount).isEqualTo(0);

        Integer settingsCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM tenant_settings ts
                JOIN tenants t ON t.id = ts.tenant_id
                WHERE t.slug = ?
                """,
                Integer.class,
                slug
        );

        assertThat(settingsCount).isEqualTo(0);
    }
}

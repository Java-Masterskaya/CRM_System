package ru.practicum.crm.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;

class TenantServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantService service;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantSettingsRepository settingsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createTenant_savesTenantAndDefaultSettings() {
        TenantDto actual = service.createTenant(
                new TenantDto("Integration Tenant", "tenant-slug", false)
        );

        assertThat(actual)
                .isEqualTo(new TenantDto("Integration Tenant",
                        "tenant-slug", true));

        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE name = ?",
                UUID.class,
                "Integration Tenant"
        );

        Tenant savedTenant = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(savedTenant.getName()).isEqualTo("Integration Tenant");
        assertThat(savedTenant.isActive()).isTrue();

        TenantSettings savedSettings = settingsRepository.findByTenantId(tenantId)
                .orElseThrow();
        assertThat(savedSettings.getTenantId()).isEqualTo(tenantId);
        assertThat(savedSettings.getTimezone()).isEqualTo(ZoneId.of("UTC"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permissions WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(25);
        assertThat(jdbcTemplate.queryForList(
                "SELECT day_of_week FROM working_hours WHERE tenant_id = ?"
                + " AND start_time = '09:00' AND end_time = '18:00'",
                String.class,
                tenantId
        )).containsExactlyInAnyOrder(
                "MONDAY",
                "TUESDAY",
                "WEDNESDAY",
                "THURSDAY",
                "FRIDAY"
        );
    }
}

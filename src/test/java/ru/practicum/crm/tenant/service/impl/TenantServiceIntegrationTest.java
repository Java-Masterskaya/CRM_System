package ru.practicum.crm.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;
import ru.practicum.crm.tenant.service.TenantService;

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
                new TenantDto("Integration Tenant", false)
        );

        assertThat(actual)
                .isEqualTo(new TenantDto("Integration Tenant", true));

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
    }
}

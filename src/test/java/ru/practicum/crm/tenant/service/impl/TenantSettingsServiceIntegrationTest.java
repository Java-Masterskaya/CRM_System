package ru.practicum.crm.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import jakarta.transaction.Transactional;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.tenant.context.TenantContext;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;
import ru.practicum.crm.tenant.service.TenantSettingsService;

class TenantSettingsServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantSettingsRepository settingsRepository;

    @Autowired
    private TenantSettingsService service;

    @MockBean
    private TenantContext tenantContext;

    @Test
    @Transactional
    void getTenantSettings_whenCurrentTenantIsB_doesNotReturnSettingsOfA() {
        Tenant tenantA = tenantRepository.save(new Tenant("Tenant A"));
        Tenant tenantB = tenantRepository.save(new Tenant("Tenant B"));

        TenantSettings settingsA = new TenantSettings(
                tenantA,
                ZoneId.of("Europe/Moscow")
        );

        settingsRepository.save(settingsA);

        when(tenantContext.getCurrentTenantId())
                .thenReturn(tenantB.getId());

        assertThatThrownBy(() -> service.getTenantSettings())
                .isInstanceOf(NotFoundException.class);
    }
}

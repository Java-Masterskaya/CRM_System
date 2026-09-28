package ru.practicum.crm.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.api.mapper.TenantMapper;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;

@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantMapper mapper;

    private TenantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantServiceImpl(tenantRepository, mapper);
    }

    @Test
    void createTenant_savesTenantWithDefaultSettingsAndReturnsMappedDto() {
        TenantDto request = new TenantDto("Tenant name", false);
        TenantDto expected = new TenantDto("Tenant name", true);

        when(tenantRepository.save(any(Tenant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(mapper.toDto(any(Tenant.class)))
                .thenReturn(expected);

        TenantDto actual = service.createTenant(request);

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<Tenant> tenantCaptor =
                ArgumentCaptor.forClass(Tenant.class);

        verify(tenantRepository).save(tenantCaptor.capture());
        verify(mapper).toDto(tenantCaptor.getValue());

        Tenant savedTenant = tenantCaptor.getValue();

        assertThat(savedTenant.getName())
                .isEqualTo(request.name());

        assertThat(savedTenant.isActive())
                .isTrue();

        assertThat(savedTenant.getSettings())
                .isNotNull();

        TenantSettings settings = savedTenant.getSettings();

        assertThat(settings.getTenant())
                .isSameAs(savedTenant);

        assertThat(settings.getTimezone())
                .isEqualTo(ZoneId.of("UTC"));

        InOrder order = inOrder(tenantRepository, mapper);
        order.verify(tenantRepository).save(savedTenant);
        order.verify(mapper).toDto(savedTenant);
    }
}

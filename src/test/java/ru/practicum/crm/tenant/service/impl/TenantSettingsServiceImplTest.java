package ru.practicum.crm.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;
import ru.practicum.crm.tenant.api.mapper.TenantSettingsMapper;
import ru.practicum.crm.tenant.context.TenantContext;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;

@ExtendWith(MockitoExtension.class)
class TenantSettingsServiceImplTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private TenantSettingsRepository repository;

    @Mock
    private TenantSettingsMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    @Mock
    private TenantContext tenantContext;

    private TenantSettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantSettingsServiceImpl(repository, mapper,
                transactionTemplate, tenantContext);
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
    }

    @Test
    void getTenantSettings_whenSettingsExist_returnsMappedDto() {
        Tenant tenant = new Tenant("Test Tenant");
        TenantSettings settings = new TenantSettings(tenant,
                ZoneId.of("Europe/Moscow"));
        TenantSettingsDto expected = new TenantSettingsDto("Europe/Moscow");
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(settings));
        when(mapper.toDto(settings)).thenReturn(expected);

        TenantSettingsDto actual = service.getTenantSettings();

        assertThat(actual).isSameAs(expected);
        verify(repository).findByTenantId(TENANT_ID);
        verify(mapper).toDto(settings);
    }

    @Test
    void getTenantSettings_whenSettingsDoNotExist_throwsNotFound() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTenantSettings())
                .isInstanceOf(NotFoundException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(mapper, never()).toDto(any(TenantSettings.class));
    }

    @Test
    void updateTenantSettings_whenTimezoneProvided_updatesAndReturnsMappedDto() {
        Tenant tenant = new Tenant("Test Tenant");
        TenantSettings settings = new TenantSettings(tenant,
                ZoneId.of("UTC"));
        final TenantSettingsDto request = new TenantSettingsDto("Europe/Paris");
        TenantSettingsDto expected = new TenantSettingsDto("Europe/Paris");
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(settings));
        when(mapper.toDto(settings)).thenReturn(expected);
        executeTransactionCallback();

        TenantSettingsDto actual = service.updateTenantSettings(request);

        assertThat(settings.getTimezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(actual).isSameAs(expected);
        verify(repository).findByTenantId(TENANT_ID);
        verify(mapper).toDto(settings);
    }

    @Test
    void updateTenantSettings_whenTimezoneIsNull_keepsExistingTimezone() {
        Tenant tenant = new Tenant("Test Tenant");
        TenantSettings settings = new TenantSettings(tenant, ZoneId.of("Europe/Moscow"));
        final TenantSettingsDto request = new TenantSettingsDto(null);
        TenantSettingsDto expected = new TenantSettingsDto("Europe/Moscow");
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(settings));
        when(mapper.toDto(settings)).thenReturn(expected);
        executeTransactionCallback();

        TenantSettingsDto actual = service.updateTenantSettings(request);

        assertThat(settings.getTimezone()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void updateTenantSettings_whenSettingsDoNotExist_throwsNotFound() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
        executeTransactionCallback();

        assertThatThrownBy(() ->
                service.updateTenantSettings(new TenantSettingsDto("UTC")))
                .isInstanceOf(NotFoundException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(mapper, never()).toDto(any(TenantSettings.class));
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionCallback() {
        when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<TenantSettingsDto> callback =
                            invocation.getArgument(0);
                    return callback.doInTransaction(transactionStatus);
                });
    }
}

package ru.practicum.crm.tenant.service;

import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;

@Service
public class TenantTimezoneProviderService implements TenantTimezoneProvider {

    private final TenantSettingsRepository repository;

    public TenantTimezoneProviderService(TenantSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public ZoneId timezoneOf(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .map(TenantSettings::getTimezone)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Настройки арендатора не найдены."));
    }
}

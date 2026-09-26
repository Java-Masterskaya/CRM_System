package ru.practicum.crm.tenant.service.impl;

import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;
import ru.practicum.crm.tenant.api.mapper.TenantSettingsMapper;
import ru.practicum.crm.tenant.context.TenantContext;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;
import ru.practicum.crm.tenant.service.TenantSettingsService;

@Service
@RequiredArgsConstructor
public class TenantSettingsServiceImpl implements TenantSettingsService {

    private final TenantSettingsRepository repository;
    private final TenantSettingsMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final TenantContext tenantContext;

    @Override
    public TenantSettingsDto getTenantSettings() {
        UUID tenantId = tenantContext.getCurrentTenantId();
        TenantSettings settings = findByTenantId(tenantId);
        return mapper.toDto(settings);
    }

    @Override
    public TenantSettingsDto updateTenantSettings(TenantSettingsDto tenantSettingsDto) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        return transactionTemplate.execute(status -> {
            TenantSettings settings = findByTenantId(tenantId);
            updateSettings(settings, tenantSettingsDto);
            return mapper.toDto(settings);
        });
    }

    private TenantSettings findByTenantId(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElseThrow(
                () -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Не найдены настройки у арендатора с id = " + tenantId)
        );
    }

    private static void updateSettings(TenantSettings toUpdate, TenantSettingsDto dto) {
        setIfNotNull(dto.timezone(), tz -> toUpdate.setTimezone(ZoneId.of(tz)));
    }

    private static <T> void setIfNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}

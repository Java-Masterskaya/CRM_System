package ru.practicum.crm.tenant.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDTO;
import ru.practicum.crm.tenant.api.mapper.TenantSettingsMapper;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;
import ru.practicum.crm.tenant.service.TenantSettingsService;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class TenantSettingsServiceImpl implements TenantSettingsService {

    private final TenantSettingsRepository repository;
    private final TenantSettingsMapper mapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public TenantSettingsDTO getTenantSettings() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001"); //TODO: переписать на корректный из токена
        TenantSettings settings = findByTenantId(tenantId);
        return mapper.toDto(settings);
    }

    @Override
    public TenantSettingsDTO updateTenantSettings(TenantSettingsDTO tenantSettingsDTO) {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000002"); //TODO: переписать на корректный из токена
        return transactionTemplate.execute(status -> {
            TenantSettings settings = findByTenantId(tenantId);
            updateSettings(settings, tenantSettingsDTO);
            return mapper.toDto(settings);
        });
    }

    private TenantSettings findByTenantId(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElseThrow(
                () -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Не найдены настройки у арендатора с id = " + tenantId)
        );
    }

    private static void updateSettings(TenantSettings toUpdate, TenantSettingsDTO dto) {
        setIfNotNull(dto.timezone(), tz -> toUpdate.setTimezone(ZoneId.of(tz)));
    }

    private static <T> void setIfNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}

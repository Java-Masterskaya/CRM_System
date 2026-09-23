package ru.practicum.crm.tenant.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDTO;
import ru.practicum.crm.tenant.domain.TenantSettings;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TenantSettingsMapper {
    TenantSettingsDTO toDto(TenantSettings tenantSettings);
}

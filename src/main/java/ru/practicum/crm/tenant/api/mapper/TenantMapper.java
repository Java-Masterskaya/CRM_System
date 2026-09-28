package ru.practicum.crm.tenant.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.domain.Tenant;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TenantMapper {
    TenantDto toDto(Tenant tenant);
}

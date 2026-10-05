package ru.practicum.crm.user.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.UserDto;
import ru.practicum.crm.user.domain.UserEntity;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {
    UserDto toDto(UserEntity user);

    AuthenticatedUserDto toAuthenticatedUserDto(UserEntity user);
}

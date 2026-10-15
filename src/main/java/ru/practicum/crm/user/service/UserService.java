package ru.practicum.crm.user.service;

import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.ClientProfileDto;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UpdateClientProfileRequest;
import ru.practicum.crm.user.api.dto.UserDto;

public interface UserService {
    UserDto create(CreateUserRequest request);

    void registerClient(ClientRegistrationRequest request);

    ClientProfileDto getClientProfile();

    ClientProfileDto updateClientProfile(UpdateClientProfileRequest request);

    void changePassword(ChangePasswordRequest request);
}

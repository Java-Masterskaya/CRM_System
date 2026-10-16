package ru.practicum.crm.user.service;

import org.springframework.security.access.prepost.PreAuthorize;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.ClientProfileDto;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UpdateClientProfileRequest;
import ru.practicum.crm.user.api.dto.UserDto;

public interface UserService {
    UserDto create(CreateUserRequest request);

    void registerClient(ClientRegistrationRequest request);

    /** Право проверяется на методе (T-030): правило по адресу лишь пропускает запрос с токеном. */
    @PreAuthorize("hasAuthority('PROFILE_READ_OWN')")
    ClientProfileDto getClientProfile();

    @PreAuthorize("hasAuthority('PROFILE_EDIT_OWN')")
    ClientProfileDto updateClientProfile(UpdateClientProfileRequest request);

    void changePassword(ChangePasswordRequest request);
}

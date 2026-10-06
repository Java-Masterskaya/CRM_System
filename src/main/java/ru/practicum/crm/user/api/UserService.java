package ru.practicum.crm.user.api;

import java.util.UUID;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UserDto;

public interface UserService {
    UserDto create(CreateUserRequest request);

    void changePassword(ChangePasswordRequest request);

    AuthenticatedUserDto authenticate(UUID tenantId, String email, String password);
}

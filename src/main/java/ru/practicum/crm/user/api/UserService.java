package ru.practicum.crm.user.api;

import java.util.Optional;
import java.util.UUID;
import ru.practicum.crm.user.api.dto.ActiveUserDto;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UserDto;

public interface UserService {
    UserDto create(CreateUserRequest request);

    void changePassword(ChangePasswordRequest request);

    AuthenticatedUserDto authenticate(UUID tenantId, String email, String password);

    /**
     * Может ли пользователь работать в системе — то же условие, что при входе: активен и не
     * удалён. Нужен обмену refresh-токена: пользователь, заблокированный после входа, не должен
     * получать новые токены.
     *
     * @return {@code false} и для пользователя, которого у арендатора нет
     */
    boolean canLogIn(UUID tenantId, UUID userId);

    Optional<ActiveUserDto> findActiveInTenant(UUID userId, UUID tenantId);
}

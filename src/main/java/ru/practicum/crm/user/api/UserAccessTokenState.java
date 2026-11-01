package ru.practicum.crm.user.api;

import java.util.Optional;
import java.util.UUID;
import ru.practicum.crm.user.api.dto.UserTokenState;

/** Состояние доступа без чтения ролей и прав при проверке предъявленного JWT. */
public interface UserAccessTokenState {

    /** Блокирует пользователя до завершения текущей транзакции выдачи токенов. */
    Optional<UserTokenState> lockForIssuance(UUID tenantId, UUID userId);

    /** Проверяет отзыв через индекс пользователей с ограничениями доступа. */
    boolean isRevoked(UUID tenantId, UUID userId, long version);
}

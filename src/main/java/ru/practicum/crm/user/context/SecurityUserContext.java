package ru.practicum.crm.user.context;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.security.CurrentUser;

/**
 * Текущий пользователь для пакета пользователей. Идентификатор из access-токена берёт
 * {@link CurrentUser} — так же, как во всём приложении (T-030): разбор токена в одном месте.
 */
@Component
public class SecurityUserContext implements UserContext {

    private final CurrentUser currentUser;

    public SecurityUserContext(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * Идентификатор текущего пользователя.
     *
     * @throws ApiException {@code UNAUTHENTICATED}, если запрос пришёл без токена
     */
    @Override
    public UUID getCurrentUserId() {
        return currentUser.id();
    }
}

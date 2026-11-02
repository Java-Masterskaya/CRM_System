package ru.practicum.crm.user.api;

import java.util.UUID;

/** Отзыв refresh-токенов пользователя без зависимости пользовательского модуля от security. */
public interface UserRefreshTokenRevoker {

    /**
     * Отзывает все refresh-токены в текущей транзакции. Принадлежность пользователя
     * текущему арендатору проверяет вызывающий сервис до обращения к этому порту.
     */
    void revokeAll(UUID userId);
}

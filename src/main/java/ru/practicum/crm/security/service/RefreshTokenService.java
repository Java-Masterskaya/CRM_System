package ru.practicum.crm.security.service;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenService {

    String create(UUID userId, UUID tenantId);

    /**
     * Обменивает действующий refresh-токен на новый той же цепочки входа (T-027).
     *
     * <p>Повторное предъявление уже использованного токена значит, что его копия есть у кого-то
     * ещё (T-028): отзывается вся цепочка входа, и ни один её токен больше не обменивается —
     * доступ возвращает только повторный вход.
     *
     * <p>Отказ — пустой результат, а не исключение: отзыв цепочки должен зафиксироваться, а
     * исключение откатило бы транзакцию вместе с ним.
     *
     * @return данные для новой пары; пусто, если токен не обменян — пустой, неизвестен,
     *     отозван, уже использован или просрочен
     */
    Optional<RefreshTokenRotation> rotate(String rawRefreshToken);
}

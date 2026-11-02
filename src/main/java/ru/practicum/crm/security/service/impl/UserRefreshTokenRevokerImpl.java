package ru.practicum.crm.security.service.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.security.service.RefreshTokenService;
import ru.practicum.crm.user.api.UserRefreshTokenRevoker;

/** Отзыв refresh-токенов вместе с административным изменением пользователя. */
@Service
@RequiredArgsConstructor
public class UserRefreshTokenRevokerImpl implements UserRefreshTokenRevoker {

    private final RefreshTokenService refreshTokenService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(UUID userId) {
        refreshTokenService.revokeAll(userId);
    }
}

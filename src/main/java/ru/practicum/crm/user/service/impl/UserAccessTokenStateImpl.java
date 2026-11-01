package ru.practicum.crm.user.service.impl;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.user.api.UserAccessTokenState;
import ru.practicum.crm.user.api.dto.UserTokenState;
import ru.practicum.crm.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserAccessTokenStateImpl implements UserAccessTokenState {

    private final UserRepository userRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UserTokenState> lockForIssuance(UUID tenantId, UUID userId) {
        return userRepository.findTokenStateForUpdate(tenantId, userId)
                .map(state -> new UserTokenState(state.getVersion(), state.getActive()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRevoked(UUID tenantId, UUID userId, long version) {
        return version < 0 || userRepository.isAccessTokenRevoked(tenantId, userId, version);
    }
}

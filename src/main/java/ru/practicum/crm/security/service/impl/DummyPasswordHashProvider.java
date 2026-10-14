package ru.practicum.crm.security.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.practicum.crm.security.api.dto.DummyPasswordHash;
import ru.practicum.crm.user.api.PasswordHashProvider;

@Component
@RequiredArgsConstructor
public class DummyPasswordHashProvider implements PasswordHashProvider {
    private final DummyPasswordHash dummyPasswordHash;

    @Override
    public String dummyHash() {
        return dummyPasswordHash.value();
    }
}

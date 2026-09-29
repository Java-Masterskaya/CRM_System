package ru.practicum.crm.user.context;

import java.util.UUID;
import org.springframework.stereotype.Component;

// TODO: Необходимо заменить на реальную реализацию,
//  которая будет получать текущего пользователя из контекста безопасности.
@Component
public class StubUserContext implements UserContext {

    private static final UUID STUB_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public UUID getCurrentUserId() {
        return STUB_USER_ID;
    }
}

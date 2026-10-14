package ru.practicum.crm.user.context;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.security.AuthenticatedUser;

@Component
public class SecurityUserContext implements UserContext {

    @Override
    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)
                || principal.userId() == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "Требуется аутентификация.");
        }
        return principal.userId();
    }
}

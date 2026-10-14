
package ru.practicum.crm.user.context;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;

@Component
public class SecurityUserContext implements UserContext {

    @Override
    public UUID getCurrentUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "Требуется аутентификация."
            );
        }

        String subject = jwtAuthentication.getToken().getSubject();

        if (subject == null) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "Требуется аутентификация."
            );
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "Требуется аутентификация."
            );
        }
    }
}

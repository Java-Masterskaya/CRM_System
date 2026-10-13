package ru.practicum.crm.user.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.practicum.crm.common.error.ApiException;

class SecurityUserContextTest {

    private static final UUID USER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final SecurityUserContext userContext = new SecurityUserContext();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedJwt_returnsUserIdFromSubject() {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(userContext.getCurrentUserId()).isEqualTo(USER_ID);
    }

    @Test
    void missingAuthentication_throwsUnauthenticatedException() {
        assertThatThrownBy(userContext::getCurrentUserId)
                .isInstanceOf(ApiException.class);
    }
}

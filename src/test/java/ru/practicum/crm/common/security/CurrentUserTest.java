package ru.practicum.crm.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;

/** Текущий пользователь из контекста безопасности: идентификатор и права из токена. */
class CurrentUserTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void id_whenAuthenticatedByToken_isSubjectOfToken() {
        authenticate(USER_ID.toString(), "REQUEST_READ_OWN");

        assertThat(currentUser.id()).isEqualTo(USER_ID);
    }

    @Test
    void hasPermission_isTrueOnlyForPermissionsFromToken() {
        authenticate(USER_ID.toString(), "REQUEST_READ_OWN", "REQUEST_CREATE");

        assertThat(currentUser.hasPermission("REQUEST_READ_OWN")).isTrue();
        assertThat(currentUser.hasPermission("REQUEST_READ_ALL")).isFalse();
    }

    @Test
    void id_withoutAuthentication_isUnauthenticated() {
        ApiException thrown = catchThrowableOfType(currentUser::id, ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(currentUser.hasPermission("REQUEST_READ_OWN")).isFalse();
    }

    /** Анонимного пользователя Spring считает аутентифицированным, но токена у него нет. */
    @Test
    void id_forAnonymousUser_isUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        ApiException thrown = catchThrowableOfType(currentUser::id, ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(currentUser.hasPermission("REQUEST_READ_OWN")).isFalse();
    }

    private static void authenticate(String subject, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                subject, null, AuthorityUtils.createAuthorityList(permissions)));
    }
}

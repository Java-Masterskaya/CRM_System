package ru.practicum.crm.security.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.testsupport.AuthTestFixture;

/** Выход по HTTP (T-029, DoD #30): ответ, повторный выход и обмен после выхода. */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class AuthLogoutIntegrationTest extends BaseIntegrationTest {

    private static final String TENANT_SLUG = "auth-logout-test";
    private static final String EMAIL = "logout-user@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @BeforeEach
    void setUp() {
        authTestFixture.createUser(authTestFixture.createTenant(TENANT_SLUG), EMAIL, PASSWORD);
    }

    /** DoD: после выхода обмен отозванного refresh токены не выдаёт. Успешный выход — 204. */
    @Test
    void logout_returnsNoContentAndTokenNoLongerExchanges() throws Exception {
        String refreshToken = loginAndGetRefreshToken();

        send("/auth/logout", refreshToken)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        send("/auth/refresh", refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /**
     * DoD: повторный выход с тем же токеном не приводит к ошибке сервера. Неизвестный токен —
     * тоже 204: по ответу нельзя проверить, жив ли чужой токен.
     */
    @Test
    void logout_repeatedOrWithUnknownToken_isStillNoContent() throws Exception {
        String refreshToken = loginAndGetRefreshToken();
        send("/auth/logout", refreshToken).andExpect(status().isNoContent());

        send("/auth/logout", refreshToken).andExpect(status().isNoContent());
        send("/auth/logout", "unknown-refresh-token").andExpect(status().isNoContent());
    }

    @Test
    void logout_withoutToken_isValidationError() throws Exception {
        send("/auth/logout", " ").andExpect(status().isBadRequest());
    }

    private ResultActions send(String path, String refreshToken) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private String loginAndGetRefreshToken() {
        return authService.authenticate(new LoginRequest(TENANT_SLUG, EMAIL, PASSWORD))
                .refreshToken();
    }
}

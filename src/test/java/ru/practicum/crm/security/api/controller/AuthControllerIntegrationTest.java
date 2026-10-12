package ru.practicum.crm.security.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class AuthControllerIntegrationTest extends BaseIntegrationTest {

    private static final String TENANT_SLUG = "auth-controller-test";
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_whenCredentialsAreValid_returnsAccessAndRefreshTokens()
            throws Exception {
        createUser();

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                        {
                                                          "tenantSlug": "auth-controller-test",
                                                          "email": "user@example.com",
                                                          "password": "StrongPassword1!"
                                                        }
                                        """
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void login_whenPasswordIsIncorrect_returnsUnauthorized()
            throws Exception {
        createUser();

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                        {
                                                          "tenantSlug": "auth-controller-test",
                                                          "email": "user@example.com",
                                                          "password": "WrongPassword2!"
                                                        }
                                        """
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_CREDENTIALS")
                );
    }

    @Test
    void refresh_whenTokenIsValid_returnsNewPairAndRejectsReuse()
            throws Exception {
        createUser();

        String oldRefreshToken = loginAndGetRefreshToken();

        MvcResult result = mockMvc.perform(
                        post("/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("refreshToken", oldRefreshToken)
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString()
        );

        String newRefreshToken = response.path("refreshToken").asText();

        assertThat(newRefreshToken)
                .isNotBlank()
                .isNotEqualTo(oldRefreshToken);

        mockMvc.perform(
                        post("/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("refreshToken", oldRefreshToken)
                                ))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refresh_whenTokenIsUnknown_returnsUnauthorized() throws Exception {
        mockMvc.perform(
                        post("/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("refreshToken", "unknown-refresh-token")
                                ))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private String loginAndGetRefreshToken() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                        {
                                                          "tenantSlug": "auth-controller-test",
                                                          "email": "user@example.com",
                                                          "password": "StrongPassword1!"
                                                        }
                                        """
                                )
                )
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString()
        );

        return response.path("refreshToken").asText();
    }

    private void createUser() {
        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant(TENANT_SLUG);

        authTestFixture.createUser(
                tenant,
                EMAIL,
                PASSWORD
        );
    }
}

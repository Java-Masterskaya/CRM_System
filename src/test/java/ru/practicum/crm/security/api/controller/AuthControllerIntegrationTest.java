package ru.practicum.crm.security.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;

@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends BaseIntegrationTest {

    private static final String TENANT_SLUG = "auth-controller-test";
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void login_whenCredentialsAreValid_returnsAccessAndRefreshTokens() throws Exception {
        createUser();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantSlug": "auth-controller-test",
                                  "email": "user@example.com",
                                  "password": "StrongPassword1!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void login_whenPasswordIsIncorrect_returnsUnauthorized() throws Exception {
        createUser();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantSlug": "auth-controller-test",
                                  "email": "user@example.com",
                                  "password": "WrongPassword2!"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private void createUser() {
        Tenant tenant = tenantRepository.save(
                new Tenant("Auth controller test tenant", TENANT_SLUG, true));
        userRepository.save(new UserEntity(
                tenant.getId(),
                EMAIL,
                passwordEncoder.encode(PASSWORD),
                UserStatus.ACTIVE
        ));
    }
}

package ru.practicum.crm.system.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingRequest;

@AutoConfigureMockMvc
class SystemTenantControllerIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_PASSWORD = "StrongPassword1!";
    private static final String TEST_SYSTEM_SECRET = "test-system-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void onboard_whenSystemSecretIsValid_createsTenant()
            throws Exception {

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "HTTP Tenant",
                        "http-tenant",
                        "admin@http.test",
                        ADMIN_PASSWORD
                );

        mockMvc.perform(
                        post("/system/tenants")
                                .header(
                                        "X-System-Secret",
                                        TEST_SYSTEM_SECRET
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated());

        assertThat(countTenantsBySlug("http-tenant"))
                .isEqualTo(1);
    }

    @Test
    void onboard_whenSystemSecretIsMissing_returnsUnauthorized()
            throws Exception {

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "No Secret Tenant",
                        "no-secret-tenant",
                        "admin@no-secret.test",
                        ADMIN_PASSWORD
                );

        mockMvc.perform(
                        post("/system/tenants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized());

        assertThat(countTenantsBySlug("no-secret-tenant"))
                .isEqualTo(0);
    }

    @Test
    void onboard_whenSystemSecretIsInvalid_returnsUnauthorized()
            throws Exception {

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "Invalid Secret Tenant",
                        "invalid-secret-tenant",
                        "admin@invalid-secret.test",
                        ADMIN_PASSWORD
                );

        mockMvc.perform(
                        post("/system/tenants")
                                .header("X-System-Secret", "wrong-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized());

        assertThat(countTenantsBySlug("invalid-secret-tenant"))
                .isEqualTo(0);
    }

    @Test
    void onboard_whenUserAuthenticationIsPresentWithoutSystemSecret_returnsUnauthorized()
            throws Exception {

        SystemTenantOnboardingRequest request =
                new SystemTenantOnboardingRequest(
                        "User Token Tenant",
                        "user-token-tenant",
                        "admin@user-token.test",
                        ADMIN_PASSWORD
                );

        mockMvc.perform(
                        post("/system/tenants")
                                .with(
                                        authentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        "user",
                                                        null,
                                                        List.of()
                                                )
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized());

        assertThat(countTenantsBySlug("user-token-tenant"))
                .isEqualTo(0);
    }

    private int countTenantsBySlug(String slug) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?",
                Integer.class,
                slug
        );

        return count != null ? count : 0;
    }
}

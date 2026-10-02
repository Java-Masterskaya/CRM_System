package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@WebMvcTest(controllers = SecurityConfigIntegrationTest.TestController.class)
@Import({
    SecurityConfig.class,
    SecurityConfigIntegrationTest.TestTenantFilterConfiguration.class
})
class SecurityConfigIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @MockBean
    private TenantContext tenantContext;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @RestController
    static class TestController {

        @GetMapping("/route-added-without-security-rule")
        String protectedRoute() {
            return "protected";
        }
    }

    @TestConfiguration
    static class TestTenantFilterConfiguration {

        @Bean
        TenantContextFilter tenantContextFilter(
                TenantActiveChecker tenantActiveChecker,
                ObjectMapper objectMapper,
                TenantContext tenantContext) {
            return new TenantContextFilter(tenantActiveChecker, objectMapper, tenantContext);
        }
    }

    @Test
    void givenNoCredentials_whenCallingNewRoute_thenReturnsProblemDetails401()
            throws Exception {
        mockMvc.perform(get("/route-added-without-security-rule").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Войдите в систему и повторите запрос."))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void givenClientPermission_whenCallingAdminRoute_thenReturnsProblemDetails403()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(user("client").authorities(
                                new SimpleGrantedAuthority("REQUEST_READ_OWN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("У вас нет прав на эту операцию."));
    }

    @Test
    void givenManageOnly_whenReadingTenantSettings_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("TENANT_SETTINGS_MANAGE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void givenAuthenticatedUser_whenCallingSystemRoute_thenDeniesAccess()
            throws Exception {
        mockMvc.perform(get("/system/tenant").with(user("operator").authorities(
                                new SimpleGrantedAuthority("REQUEST_READ_ALL"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void givenNoCredentials_whenCallingAuthOperations_thenOnlyRegistrationLoginAndRefreshAreOpen()
            throws Exception {
        for (String path : new String[] {"/auth/register", "/auth/login", "/auth/refresh"}) {
            mockMvc.perform(post(path).with(anonymous()))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(post("/auth/logout").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}

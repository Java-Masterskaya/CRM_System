package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import ru.practicum.crm.common.health.HealthController;
import ru.practicum.crm.security.system.SystemFilterConfig;
import ru.practicum.crm.security.system.SystemSecretProperties;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantContextFilter;

@WebMvcTest(controllers = {
    SecurityConfigSliceTest.TestController.class,
    HealthController.class
})
@TestPropertySource(properties = {
        "app.security.authorization.enabled=false",
        "crm.system.secret=test-system-secret"
})
@Import({
    SecurityConfig.class,
    SystemFilterConfig.class,
    SecurityConfigSliceTest.TestTenantFilterConfiguration.class
})
class SecurityConfigSliceTest {

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
                .addFilters(new SecurityTestRequestIdFilter())
                .apply(springSecurity())
                .build();
    }

    @RestController
    public static class TestController {

        @GetMapping("/route-added-without-security-rule")
        public String protectedRoute() {
            return "protected";
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(SystemSecretProperties.class)
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
    void givenNoCredentials_whenCallingNewRouteBeforeAuthentication_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(get("/route-added-without-security-rule").with(anonymous()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @Test
    void givenNoCredentials_whenCallingHealth_thenReturnsOk() throws Exception {
        mockMvc.perform(get("/health").with(anonymous()))
                .andExpect(status().isOk());
    }

    @Test
    void givenNoSystemSecret_whenCallingSystemRoute_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/system/tenant").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givenValidSystemSecret_whenCallingSystemRoute_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(get("/system/tenant")
                        .header("X-System-Secret", "test-system-secret")
                        .with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenNoCredentials_whenCallingUnimplementedAuthRoutes_thenReturnsNotFound()
            throws Exception {
        for (String path : new String[] {
                "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout"
        }) {
            mockMvc.perform(post(path).with(anonymous()))
                    .andExpect(status().isNotFound());
        }
    }
}

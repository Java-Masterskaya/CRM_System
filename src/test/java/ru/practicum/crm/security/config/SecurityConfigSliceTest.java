package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
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

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private BearerTokenResolver resolver;

    @BeforeEach
    void setUpMockMvc() {
        when(jwtDecoder.decode(anyString()))
                .thenThrow(new JwtException("Invalid access token"));

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
    void givenNoCredentials_whenCallingNewRoute_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/route-added-without-security-rule").with(anonymous()))
                .andExpect(status().isUnauthorized())
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
    void givenNoCredentials_whenCallingUnimplementedPublicAuthRoutes_thenReturnsNotFound()
            throws Exception {

        for (String path : new String[] {
                "/auth/register",
                "/auth/login",
                "/auth/refresh"
        }) {
            mockMvc.perform(post(path).with(anonymous()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void givenNoCredentials_whenCallingLogout_thenReachesMvcLookup()
            throws Exception {

        mockMvc.perform(post("/auth/logout").with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/health"
    })
    void shouldIgnoreBearerTokenOnPublicEndpoints(String path) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", path);
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer expired-token"
        );

        assertNull(resolver.resolve(request));
    }

    @Test
    void shouldResolveBearerTokenOnProtectedEndpoint() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/client/profile");
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer test-access-token"
        );

        assertEquals("test-access-token", resolver.resolve(request));
    }

    @Test
    void givenExpiredBearerToken_whenCallingHealth_thenReturnsOk()
            throws Exception {

        mockMvc.perform(get("/health")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer expired-token"
                        ))
                .andExpect(status().isOk());
    }

    @Test
    void givenExpiredBearerToken_whenCallingPublicAuthRoutes_thenReturnsNotFound()
            throws Exception {

        for (String path : new String[] {
                "/auth/register",
                "/auth/login",
                "/auth/refresh"
        }) {
            mockMvc.perform(post(path)
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer expired-token"
                            ))
                    .andExpect(status().isNotFound());
        }
    }
}

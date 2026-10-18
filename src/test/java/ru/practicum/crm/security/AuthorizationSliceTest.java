package ru.practicum.crm.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import ru.practicum.crm.security.config.SecurityConfig;
import ru.practicum.crm.security.system.SystemFilterConfig;
import ru.practicum.crm.security.system.SystemSecretProperties;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;

@WebMvcTest(controllers = AuthorizationSliceTest.DummyController.class)
@TestPropertySource(properties = {
        "app.security.authorization.enabled=true",
        "crm.system.secret=test-system-secret"
})
@Import({
        SecurityConfig.class,
        SystemFilterConfig.class,
        AuthorizationSliceTest.TestTenantFilterConfiguration.class
})
class AuthorizationSliceTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @MockBean
    private TenantContext tenantContext;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private UserDetailsService userDetailsService;

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String USER_ID =
            "33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void setUp() {
        when(tenantActiveChecker.isActive(TENANT_ID)).thenReturn(true);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class ClientProfileGet {

        @Test
        void givenNoToken_whenGetClientProfile_thenReturns401() throws Exception {
            mockMvc.perform(get("/client/profile").with(anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void givenWrongPermission_whenGetClientProfile_thenReturns403() throws Exception {
            mockMvc.perform(get("/client/profile")
                            .with(jwtWithAuthority("REQUEST_READ_ALL")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void givenCorrectPermission_whenGetClientProfile_thenReachesMvcLookup()
                throws Exception {
            mockMvc.perform(get("/client/profile")
                            .with(jwtWithAuthority("PROFILE_READ_OWN")))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class ClientProfilePut {

        @Test
        void givenNoToken_whenPutClientProfile_thenReturns401() throws Exception {
            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void givenWrongPermission_whenPutClientProfile_thenReturns403() throws Exception {
            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(jwtWithAuthority("PROFILE_READ_OWN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void givenCorrectPermission_whenPutClientProfile_thenReachesMvcLookup()
                throws Exception {
            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(jwtWithAuthority("PROFILE_EDIT_OWN")))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class TenantSettingsGet {

        @Test
        void givenNoToken_whenGetTenantSettings_thenReturns401() throws Exception {
            mockMvc.perform(get("/admin/tenant/settings").with(anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void givenWrongPermission_whenGetTenantSettings_thenReturns403() throws Exception {
            mockMvc.perform(get("/admin/tenant/settings")
                            .with(jwtWithAuthority("PROFILE_READ_OWN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void givenCorrectPermission_whenGetTenantSettings_thenReachesMvcLookup()
                throws Exception {
            mockMvc.perform(get("/admin/tenant/settings")
                            .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class TenantSettingsPut {

        @Test
        void givenNoToken_whenPutTenantSettings_thenReturns401() throws Exception {
            mockMvc.perform(put("/admin/tenant/settings")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void givenWrongPermission_whenPutTenantSettings_thenReturns403() throws Exception {
            mockMvc.perform(put("/admin/tenant/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void givenCorrectPermission_whenPutTenantSettings_thenReachesMvcLookup()
                throws Exception {
            mockMvc.perform(put("/admin/tenant/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(jwtWithAuthority("TENANT_SETTINGS_MANAGE")))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class AdminAndClientDenyAll {

        @Test
        void givenSettingsReadPermission_whenAccessingAdminRoute_thenReturns403()
                throws Exception {
            mockMvc.perform(get("/admin/users")
                            .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void givenProfilePermission_whenAccessingClientRoute_thenReturns403()
                throws Exception {
            mockMvc.perform(get("/client/requests")
                            .with(jwtWithAuthority("PROFILE_READ_OWN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
            jwtWithAuthority(String authority) {
        return jwt()
                .jwt(token -> token
                        .subject(USER_ID)
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("permissions", List.of(authority)))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableConfigurationProperties(SystemSecretProperties.class)
    static class TestTenantFilterConfiguration {
        @org.springframework.context.annotation.Bean
        ru.practicum.crm.tenant.api.TenantContextFilter tenantContextFilter(
                TenantActiveChecker tenantActiveChecker,
                ObjectMapper objectMapper,
                TenantContext tenantContext) {
            return new ru.practicum.crm.tenant.api.TenantContextFilter(
                    tenantActiveChecker, objectMapper, tenantContext);
        }
    }

    @RestController
    static class DummyController {
        @GetMapping("/dummy")
        public String dummy() {
            return "ok";
        }
    }
}

package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.practicum.crm.common.health.HealthController;
import ru.practicum.crm.security.system.SystemFilterConfig;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;

@WebMvcTest(controllers = HealthController.class)
@TestPropertySource(properties = {
        "app.security.authorization.enabled=true",
        "crm.system.secret=test-system-secret"
})
@Import({
    SecurityConfig.class,
    SystemFilterConfig.class,
    SecurityConfigSliceTest.TestTenantFilterConfiguration.class
})
class SecurityAuthorizationRulesTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void givenNoCredentials_whenCallingUnmatchedRoute_thenReturnsProblemDetails401()
            throws Exception {
        mockMvc.perform(get("/route-added-without-security-rule").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(SecurityTestRequestIdFilter.HEADER_NAME,
                        "security-test-request-id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(result -> {
                    JsonNode problem = objectMapper.readTree(
                            result.getResponse().getContentAsString());
                    assertThat(problem.path("requestId").asText()).isEqualTo(
                            result.getResponse().getHeader(
                                    SecurityTestRequestIdFilter.HEADER_NAME));
                });
    }

    @Test
    void givenReadAllPermission_whenReadingTenantSettings_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(user("operator").authorities(
                                new SimpleGrantedAuthority("REQUEST_READ_ALL"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenSettingsReadPermission_whenReadingTenantSettings_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("TENANT_SETTINGS_READ"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenSettingsReadPermission_whenUpdatingTenantSettings_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(put("/admin/tenant/settings")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("TENANT_SETTINGS_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenSettingsManagePermission_whenUpdatingTenantSettings_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(put("/admin/tenant/settings")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("TENANT_SETTINGS_MANAGE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenReadAllPermission_whenCallingNewAdminRoute_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/admin/users")
                        .with(user("operator").authorities(
                                new SimpleGrantedAuthority("REQUEST_READ_ALL"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenClientPermission_whenCallingUnmappedClientRoute_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/client/requests")
                        .with(user("client").authorities(
                                new SimpleGrantedAuthority("REQUEST_READ_OWN"))))
                .andExpect(status().isForbidden());
    }
}

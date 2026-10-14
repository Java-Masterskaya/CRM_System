package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String USER_ID =
            "33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void setUpMockMvc() {
        when(tenantActiveChecker.isActive(TENANT_ID)).thenReturn(true);
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

    /**
     * Выход (T-029) — без access-токена, как и обмен: доказательство права на выход — сам
     * refresh-токен в теле запроса. Контроллера в этом срезе нет, поэтому запрос доходит до
     * поиска обработчика и получает 404, а не 401.
     */
    @Test
    void givenNoCredentials_whenLoggingOut_thenReachesMvcLookup() throws Exception {
        mockMvc.perform(post("/auth/logout").with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenReadAllPermission_whenReadingTenantSettings_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenSettingsReadPermission_whenReadingTenantSettings_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenSettingsReadPermission_whenUpdatingTenantSettings_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(put("/admin/tenant/settings")
                        .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenSettingsManagePermission_whenUpdatingTenantSettings_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(put("/admin/tenant/settings")
                        .with(jwtWithAuthority("TENANT_SETTINGS_MANAGE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenReadAllPermission_whenCallingNewAdminRoute_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/admin/users")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenClientPermission_whenCallingUnmappedClientRoute_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/client/requests")
                        .with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAnalyticsReadPermission_whenReadingInboundReport_thenReachesMvcLookup()
            throws Exception {
        mockMvc.perform(get("/analytics/request/inbound")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-10")
                        .with(jwtWithAuthority("ANALYTICS_READ")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenSettingsReadPermission_whenReadingInboundReport_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/analytics/request/inbound")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-10")
                        .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                .andExpect(status().isForbidden());
      
    private RequestPostProcessor jwtWithAuthority(String authority) {
        return jwt()
                .jwt(token -> token
                        .subject(USER_ID)
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("permissions", List.of(authority)))
                .authorities(new SimpleGrantedAuthority(authority));
    }
}

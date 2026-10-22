package ru.practicum.crm.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.api.dto.RefreshRequest;
import ru.practicum.crm.tenant.api.TenantService;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
class AuthorizationEnabledIT extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String EMAIL = "client@example.com";
    private static final String NAME = "Клиент теста";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantService tenantService;

    private String tenantSlug;
    private UUID tenantId;

    @BeforeEach
    void createTenant() {
        tenantSlug = "authorization-" + UUID.randomUUID().toString().replace("-", "");
        tenantService.createTenant(
                new TenantDto("Арендатор теста авторизации", tenantSlug, true)
        );
        tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE slug = ?",
                UUID.class,
                tenantSlug
        );
    }

    @Test
    void givenRegisteredClient_whenLoggingInAndReadingProfile_thenReturnsOwnProfile()
            throws Exception {
        registerClient();
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(get("/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.name").value(NAME));
    }

    @Test
    void givenNoToken_whenReadingClientProfile_thenReturns401() throws Exception {
        mockMvc.perform(get("/client/profile").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void givenNoAccessToken_whenCallingPublicHealthAndDocs_thenReturnsSuccess()
            throws Exception {
        mockMvc.perform(get("/health").with(anonymous()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty());
    }

    @Test
    void givenRefreshTokenWithoutAccessToken_whenLoggingOut_thenRevokesRefreshToken()
            throws Exception {
        registerClient();
        JsonNode login = login();
        String refreshToken = login.path("refreshToken").asText();

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest(refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givenClientWithoutProfileReadPermission_whenReadingProfile_thenReturns403()
            throws Exception {
        registerClient();
        jdbcTemplate.update(
                """
                        DELETE FROM role_permissions rp
                        USING roles r, permissions p
                        WHERE rp.tenant_id = ?
                          AND r.tenant_id = rp.tenant_id
                          AND r.id = rp.role_id
                          AND p.tenant_id = rp.tenant_id
                          AND p.id = rp.permission_id
                          AND r.code = 'CLIENT'
                          AND p.code = 'PROFILE_READ_OWN'
                """,
                tenantId
        );
        String accessToken = loginAndGetAccessToken();

        mockMvc.perform(get("/client/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private void registerClient() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ClientRegistrationRequest(
                                tenantSlug, EMAIL, PASSWORD, NAME))))
                .andExpect(status().isCreated());
    }

    private String loginAndGetAccessToken() throws Exception {
        return login().path("accessToken").asText();
    }

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                tenantSlug, EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

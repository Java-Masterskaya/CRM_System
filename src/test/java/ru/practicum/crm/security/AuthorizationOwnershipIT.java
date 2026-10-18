package ru.practicum.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
@Import(AuthTestFixture.class)
class AuthorizationOwnershipIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        when(tenantActiveChecker.isActive(tenantA)).thenReturn(true);
        when(tenantActiveChecker.isActive(tenantB)).thenReturn(true);
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class ClientProfileAccess {

        @BeforeEach
        void setupUsers() {
            insertTenant(tenantA, "tenant-a");
            insertUser(userA, tenantA, "client-a@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantA, "tenant-a"),
                    new AuthTestFixture.TestUser(userA),
                    "PROFILE_READ_OWN",
                    "PROFILE_EDIT_OWN"
            );

            insertTenant(tenantB, "tenant-b");
            insertUser(userB, tenantB, "client-b@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantB, "tenant-b"),
                    new AuthTestFixture.TestUser(userB),
                    "PROFILE_READ_OWN"
            );
        }

        @Test
        void givenOwnProfileAccess_withCorrectPermission_thenReturns200()
                throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userA, tenantA, List.of("PROFILE_READ_OWN"));

            mockMvc.perform(get("/client/profile")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").isNotEmpty());
        }

        @Test
        void givenOwnProfileUpdate_withCorrectPermission_thenReturns200()
                throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userA, tenantA, List.of("PROFILE_EDIT_OWN"));

            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content(
                                    """
                                            {
                                                "name": "Updated Name",
                                                "phone": "+79991234567"
                                            }
                                    """
                            ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Updated Name"));
        }

        @Test
        void givenNonExistentUser_inCurrentTenant_thenReturns404Not403()
                throws Exception {
            UUID nonExistentUserId = UUID.randomUUID();
            String token = jwtTokenService.createAccessToken(
                    nonExistentUserId, tenantA, List.of("PROFILE_READ_OWN"));

            MvcResult result = mockMvc.perform(get("/client/profile")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode problem = objectMapper.readTree(content);
            assertThat(problem).isNotNull();
            assertThat(problem.path("code").asText()).isEqualTo("NOT_FOUND");
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class TenantSettingsAccess {

        @BeforeEach
        void setupAdminUser() {
            insertTenant(tenantA, "tenant-admin");
            insertUser(userA, tenantA, "admin-a@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantA, "tenant-admin"),
                    new AuthTestFixture.TestUser(userA),
                    "TENANT_SETTINGS_READ",
                    "TENANT_SETTINGS_MANAGE"
            );
        }

        @Test
        void givenAdminSettingsRead_withCorrectPermission_thenReachesMvcLookup()
                throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userA, tenantA, List.of("TENANT_SETTINGS_READ"));

            mockMvc.perform(get("/admin/tenant/settings")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        void givenAdminSettingsManage_withCorrectPermission_thenReachesMvcLookup()
                throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userA, tenantA, List.of("TENANT_SETTINGS_MANAGE"));

            mockMvc.perform(put("/admin/tenant/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    private void insertTenant(UUID tid, String slug) {
        jdbcTemplate.update(
                """
                        INSERT INTO tenants (id, name, slug, active, created_at, updated_at)
                        VALUES (?, ?, ?, true, NOW(), NOW())
                """,
                tid, "Test tenant " + slug, slug
        );
    }

    private void insertUser(UUID uid, UUID tid, String email) {
        String passwordHash = passwordEncoder.encode("test-password-123");
        jdbcTemplate.update(
                """
                        INSERT INTO users (id, tenant_id, email, password_hash, status,
                                           deleted_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', NULL, NOW(), NOW())
                """,
                uid, tid, email, passwordHash
        );
    }
}

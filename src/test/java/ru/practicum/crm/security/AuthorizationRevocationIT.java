package ru.practicum.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.service.JwtTokenService;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=true")
@Import(AuthTestFixture.class)
class AuthorizationRevocationIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class ProfileReadRevocation {

        private UUID tenantId;
        private UUID userId;

        @BeforeEach
        void setup() {
            tenantId = UUID.randomUUID();
            userId = UUID.randomUUID();
            when(tenantActiveChecker.isActive(tenantId)).thenReturn(true);
            insertTenant(tenantId, "tenant-revoke");
            insertUser(userId, tenantId, "revoke-test@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantId, "tenant-revoke"),
                    new AuthTestFixture.TestUser(userId),
                    "PROFILE_READ_OWN"
            );
        }

        @Test
        void permissionGranted_getProfileAccessSucceeds() throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("PROFILE_READ_OWN"));

            mockMvc.perform(get("/client/profile")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").isNotEmpty());
        }

        @Test
        void permissionRevoked_getProfileAccessDenied() throws Exception {
            String tokenWithPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("PROFILE_READ_OWN"));

            mockMvc.perform(get("/client/profile")
                            .header("Authorization", "Bearer " + tokenWithPermission))
                    .andExpect(status().isOk());

            revokePermissionFromDatabase(tenantId);

            String tokenWithoutPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of());

            mockMvc.perform(get("/client/profile")
                            .header("Authorization", "Bearer " + tokenWithoutPermission))
                    .andExpect(status().isForbidden());
        }

        private void revokePermissionFromDatabase(UUID tid) {
            jdbcTemplate.update(
                    "DELETE FROM role_permissions "
                            + "WHERE permission_id IN ("
                            + "SELECT rp.permission_id FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE')",
                    tid
            );

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE'",
                    Integer.class,
                    tid
            );
            assertThat(remaining).isZero();
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class ProfileEditRevocation {

        private UUID tenantId;
        private UUID userId;

        @BeforeEach
        void setup() {
            tenantId = UUID.randomUUID();
            userId = UUID.randomUUID();
            when(tenantActiveChecker.isActive(tenantId)).thenReturn(true);
            insertTenant(tenantId, "tenant-revoke-edit");
            insertUser(userId, tenantId, "revoke-edit@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantId, "tenant-revoke-edit"),
                    new AuthTestFixture.TestUser(userId),
                    "PROFILE_EDIT_OWN"
            );
        }

        @Test
        void editPermissionGranted_putProfileSucceeds() throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("PROFILE_EDIT_OWN"));

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
        void editPermissionRevoked_putProfileAccessDenied() throws Exception {
            String tokenWithPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("PROFILE_EDIT_OWN"));

            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenWithPermission)
                            .content(
                                    """
                                            {
                                                "name": "Updated Name",
                                                "phone": "+79991234567"
                                            }
                                    """
                            ))
                    .andExpect(status().isOk());

            revokePermissionFromDatabase(tenantId);

            String tokenWithoutPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of());

            mockMvc.perform(put("/client/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + tokenWithoutPermission))
                    .andExpect(status().isForbidden());
        }

        private void revokePermissionFromDatabase(UUID tid) {
            jdbcTemplate.update(
                    "DELETE FROM role_permissions "
                            + "WHERE permission_id IN ("
                            + "SELECT rp.permission_id FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE')",
                    tid
            );

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE'",
                    Integer.class,
                    tid
            );
            assertThat(remaining).isZero();
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class TenantSettingsRevocation {

        private UUID tenantId;
        private UUID userId;

        @BeforeEach
        void setup() {
            tenantId = UUID.randomUUID();
            userId = UUID.randomUUID();
            when(tenantActiveChecker.isActive(tenantId)).thenReturn(true);
            insertTenant(tenantId, "tenant-revoke-settings");
            insertUser(userId, tenantId, "admin-revoke@test.com");
            authTestFixture.grantAllPermissions(
                    new AuthTestFixture.TestTenant(tenantId, "tenant-revoke-settings"),
                    new AuthTestFixture.TestUser(userId),
                    "TENANT_SETTINGS_READ"
            );
        }

        @Test
        void settingsReadGranted_accessAllowed() throws Exception {
            String token = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("TENANT_SETTINGS_READ"));

            mockMvc.perform(get("/admin/tenant/settings")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        void settingsReadRevoked_accessDenied() throws Exception {
            String tokenWithPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of("TENANT_SETTINGS_READ"));

            mockMvc.perform(get("/admin/tenant/settings")
                            .header("Authorization", "Bearer " + tokenWithPermission))
                    .andExpect(status().isNotFound());

            revokePermissionFromDatabase(tenantId);

            String tokenWithoutPermission = jwtTokenService.createAccessToken(
                    userId, tenantId, List.of());

            mockMvc.perform(get("/admin/tenant/settings")
                            .header("Authorization", "Bearer " + tokenWithoutPermission))
                    .andExpect(status().isForbidden());
        }

        private void revokePermissionFromDatabase(UUID tid) {
            jdbcTemplate.update(
                    "DELETE FROM role_permissions "
                            + "WHERE permission_id IN ("
                            + "SELECT rp.permission_id FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE')",
                    tid
            );

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM role_permissions rp "
                            + "JOIN roles r ON rp.role_id = r.id "
                            + "WHERE r.tenant_id = ? AND r.code = 'AUTH_TEST_ROLE'",
                    Integer.class,
                    tid
            );
            assertThat(remaining).isZero();
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

package ru.practicum.crm.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.authorization.enabled=false")
@Import(TenantContextFilterIntegrationTest.TestController.class)
class TenantContextFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantContext tenantContext;

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestController testController;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        testController.clear();
    }

    @Test
    void request_whenAuthenticatedWithTenant_passesTenantToBusinessLayer() throws Exception {

        org.mockito.Mockito.when(tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(true);

        mockMvc.perform(get("/admin/test/tenant")
                        .with(authentication(jwtAuthentication())))
                .andExpect(status().isOk());

        assertThat(testController.getTenantSeenByController()).isEqualTo(TENANT_ID);
    }

    @Test
    void request_whenAuthenticationIsMissing_passesWithoutTenantContext()
            throws Exception {

        mockMvc.perform(get("/admin/test/tenant"))
                .andExpect(status().isOk());

        assertThat(testController.getTenantSeenByController()).isNull();
    }

    @Test
    void request_whenTenantIsInactive_returnsUnauthorized() throws Exception {

        org.mockito.Mockito.when(tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(false);

        mockMvc.perform(get("/admin/test/tenant")
                        .with(authentication(jwtAuthentication())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_whenCompleted_clearsTenantContext() throws Exception {

        org.mockito.Mockito.when(
                        tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(true);

        mockMvc.perform(
                        get("/admin/test/tenant")
                                .with(authentication(jwtAuthentication())))
                .andExpect(status().isOk());

        assertThat(tenantContext.getCurrentTenantId()).isNull();
    }

    private Authentication jwtAuthentication() {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("test-user-id")
                .claim("tenant_id", TENANT_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        return new JwtAuthenticationToken(jwt, List.of());
    }

    @RestController
    static class TestController {

        @Autowired
        private TenantContext tenantContext;

        private UUID tenantSeenByController;

        void clear() {
            tenantSeenByController = null;
        }

        UUID getTenantSeenByController() {
            return tenantSeenByController;
        }

        @GetMapping("/admin/test/tenant")
        UUID tenant() {
            UUID currentTenant = tenantContext.getCurrentTenantId();
            tenantSeenByController = currentTenant;
            return currentTenant;
        }
    }
}

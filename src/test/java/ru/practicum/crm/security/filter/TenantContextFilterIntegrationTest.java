package ru.practicum.crm.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.security.principal.TenantPrincipal;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.repository.TenantRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantContextFilterIntegrationTest.TestController.class)
class TenantContextFilterIntegrationTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantActiveChecker tenantActiveChecker;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void request_whenAuthenticatedWithTenant_passesTenantToBusinessLayer()
            throws Exception {

        org.mockito.Mockito.when(
                        tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(true);

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(TENANT_ID),
                        null,
                        List.of());

        mockMvc.perform(
                        get("/test/tenant")
                                .header("X-Tenant-Id", TENANT_ID.toString())
                                .with(authentication(authentication)))
                .andExpect(status().isOk());

        assertThat(TestController.tenantSeenByController)
                .isEqualTo(TENANT_ID);
    }

    @Test
    void request_whenTenantIsMissing_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(get("/test/tenant").header("X-Tenant-Id", "missing"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_whenTenantIsInactive_returnsUnauthorized()
            throws Exception {

        org.mockito.Mockito.when(
                        tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(false);

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(TENANT_ID),
                        null,
                        List.of());

        mockMvc.perform(
                        get("/test/tenant")
                                .header("X-Tenant-Id", TENANT_ID.toString())
                                .with(authentication(authentication)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_whenCompleted_clearsTenantContext() throws Exception {

        org.mockito.Mockito.when(
                        tenantActiveChecker.isActive(TENANT_ID))
                .thenReturn(true);

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(TENANT_ID),
                        null,
                        List.of());

        mockMvc.perform(
                        get("/test/tenant")
                                .header("X-Tenant-Id", TENANT_ID.toString())
                                .with(authentication(authentication)))
                .andExpect(status().isOk());

        assertThat(TenantContext.getTenantId()).isNull();
    }

    @RestController
    static class TestController {

        private static UUID tenantSeenByController;

        @GetMapping("/test/tenant")
        UUID tenant() {
            tenantSeenByController = TenantContext.getTenantId();
            return tenantSeenByController;
        }
    }
}

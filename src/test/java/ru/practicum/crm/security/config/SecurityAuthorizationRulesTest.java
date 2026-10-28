package ru.practicum.crm.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    /**
     * Маршрут настроек открыт для любого запроса с токеном: какое право нужно, проверяет
     * {@code @PreAuthorize} на методе сервиса (T-030), а его здесь нет — контроллера в этом
     * срезе нет, и запрос доходит до поиска обработчика. Права на сами операции проверяет
     * {@code OperationPermissionsIntegrationTest}.
     */
    @Test
    void givenAnyPermission_whenCallingTenantSettings_thenUrlRulePassesItToMethodCheck()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/tenant/settings")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenNoCredentials_whenCallingTenantSettings_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/admin/tenant/settings").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Справочник типов заявок (T-034): маршруты пропускают любой запрос с токеном, право
     * проверяет сервис. Открыты только операции справочника: удаления у него нет, и
     * {@code DELETE} по-прежнему закрыт.
     */
    @Test
    void givenAnyPermission_whenManagingRequestTypes_thenUrlRulesPassOnlyDirectoryOperations()
            throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/admin/request-types").with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/request-types").with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/request-types/{id}", id)
                        .with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/request-types/{id}/deactivate", id)
                        .with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/request-types/{id}/activate", id)
                        .with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/request-types/{id}", id)
                        .with(jwtWithAuthority("REQUEST_TYPE_MANAGE")))
                .andExpect(status().isForbidden());
    }

    /** Клиентский список типов (T-034): открыто только чтение, право проверяет сервис. */
    @Test
    void givenAnyPermission_whenListingRequestTypesAsClient_thenUrlRulePassesOnlyGet()
            throws Exception {
        mockMvc.perform(get("/client/request-types").with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/client/request-types").with(jwtWithAuthority("REQUEST_CREATE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenCallingRequestTypes_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/request-types").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/client/request-types").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Смена статуса заявки (T-044) — так же, как настройки: маршрут пропускает любой запрос с
     * токеном, право {@code REQUEST_STATUS_CHANGE} проверяет сервис. Открыт только {@code PUT}:
     * другой метод на том же пути по-прежнему закрыт.
     */
    @Test
    void givenAnyPermission_whenChangingRequestStatus_thenUrlRulePassesOnlyPutToMethodCheck()
            throws Exception {
        String route = "/admin/requests/" + UUID.randomUUID() + "/status";

        mockMvc.perform(put(route).with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(route).with(jwtWithAuthority("REQUEST_STATUS_CHANGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenChangingRequestStatus_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(put("/admin/requests/" + UUID.randomUUID() + "/status")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
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
        mockMvc.perform(get("/client/unmapped-check")
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

        verify(tenantContext).setTenantId(TENANT_ID);
    }

    @Test
    void givenSettingsReadPermission_whenReadingInboundReport_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(get("/analytics/request/inbound")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-10")
                        .with(jwtWithAuthority("TENANT_SETTINGS_READ")))
                .andExpect(status().isForbidden());
    }

    /**
     * Настройки сроков (T-055–T-057): маршруты пропускают любой запрос с токеном, право
     * {@code SLA_POLICY_MANAGE} проверяет сервис. Открыты только операции настроек: удалить
     * политику или календарь нельзя, изменить запись справочника — тоже.
     */
    @Test
    void givenAnyPermission_whenManagingSlaSettings_thenUrlRulesPassOnlySettingsOperations()
            throws Exception {
        UUID id = UUID.randomUUID();
        for (String path : List.of("/admin/sla-policies", "/admin/calendar/working-hours",
                "/admin/calendar/holidays")) {
            mockMvc.perform(get(path).with(jwtWithAuthority("REQUEST_READ_ALL")))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/admin/sla-policies").with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/sla-policies/{id}", id)
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/calendar/working-hours")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/calendar/holidays")
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/calendar/holidays/{id}", id)
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/admin/sla-policies/{id}", id)
                        .with(jwtWithAuthority("SLA_POLICY_MANAGE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/calendar/working-hours")
                        .with(jwtWithAuthority("SLA_POLICY_MANAGE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/calendar/holidays/{id}", id)
                        .with(jwtWithAuthority("SLA_POLICY_MANAGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenCallingSlaSettings_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/sla-policies").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/calendar/working-hours").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/calendar/holidays").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givenStatusChangePermission_whenAssigningExecutor_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(put("/admin/requests/{id}/assignee", UUID.randomUUID())
                        .with(jwtWithAuthority("REQUEST_STATUS_CHANGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAssignPermission_whenAssigningExecutor_thenReachesMvcLookup() throws Exception {
        mockMvc.perform(put("/admin/requests/{id}/assignee", UUID.randomUUID())
                        .with(jwtWithAuthority("REQUEST_ASSIGN")))
                .andExpect(status().isNotFound());
    }

    /**
     * Автоподбор исполнителя (T-051) — та же операция назначения, поэтому и право то же:
     * {@code REQUEST_STATUS_CHANGE} для неё недостаточно.
     */
    @Test
    void givenStatusChangePermission_whenAutoAssigningExecutor_thenReturnsForbidden()
            throws Exception {
        mockMvc.perform(post("/admin/requests/{id}/auto-assign", UUID.randomUUID())
                        .with(jwtWithAuthority("REQUEST_STATUS_CHANGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAssignPermission_whenAutoAssigningExecutor_thenReachesMvcLookup() throws Exception {
        mockMvc.perform(post("/admin/requests/{id}/auto-assign", UUID.randomUUID())
                        .with(jwtWithAuthority("REQUEST_ASSIGN")))
                .andExpect(status().isNotFound());
    }

    /**
     * Взятие заявки в работу: POST пропускает любой аутентифицированный
     * запрос, бизнес-права проверяет сервис. Другие HTTP-методы закрыты.
     */
    @Test
    void givenAnyPermission_whenTakingRequest_thenUrlRulePassesOnlyPostToMethodCheck()
            throws Exception {
        String route = "/admin/requests/" + UUID.randomUUID() + "/take";

        mockMvc.perform(post(route).with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(route).with(jwtWithAuthority("REQUEST_STATUS_CHANGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenTakingRequest_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(post("/admin/requests/" + UUID.randomUUID() + "/take")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Реестр заявок (T-040): маршрут пропускает любой запрос с токеном, право
     * {@code REQUEST_READ_ALL} проверяет сервис. Открыто только чтение: другой метод на том же
     * пути по-прежнему закрыт.
     */
    @Test
    void givenAnyPermission_whenReadingRequestRegistry_thenUrlRulePassesOnlyGetToMethodCheck()
            throws Exception {
        mockMvc.perform(get("/admin/requests").with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/requests").with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenReadingRequestRegistry_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/admin/requests").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Карточка заявки (T-041) и редактирование заявки (T-042):
     * GET и PUT требуют разные права доступа.
     */
    @Test
    void givenPermissions_whenAccessingRequestCard_thenUrlRulesCheckRequiredPermission()
            throws Exception {
        String route = "/admin/requests/" + UUID.randomUUID();

        mockMvc.perform(get(route).with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(route).with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(route).with(jwtWithAuthority("REQUEST_EDIT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenNoCredentials_whenEditingRequest_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(put("/admin/requests/" + UUID.randomUUID())
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givenNoCredentials_whenReadingRequestCard_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/requests/" + UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * История переходов заявки (T-041): тот же принцип, что у карточки — маршрут открыт любому
     * токену, право {@code REQUEST_READ_ALL} проверяет сервис.
     */
    @Test
    void givenAnyPermission_whenReadingStatusHistory_thenUrlRulePassesOnlyGetToMethodCheck()
            throws Exception {
        String route = "/admin/requests/" + UUID.randomUUID() + "/status-history";

        mockMvc.perform(get(route).with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(route).with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenReadingStatusHistory_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/requests/" + UUID.randomUUID() + "/status-history")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Журнал аудита заявки (T-041): маршрут открыт любому токену, право
     * {@code REQUEST_AUDIT_READ} проверяет сервис — отдельное от {@code REQUEST_READ_ALL}.
     */
    @Test
    void givenAnyPermission_whenReadingAuditLog_thenUrlRulePassesOnlyGetToMethodCheck()
            throws Exception {
        String route = "/admin/requests/" + UUID.randomUUID() + "/audit-log";

        mockMvc.perform(get(route).with(jwtWithAuthority("REQUEST_AUDIT_READ")))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(route).with(jwtWithAuthority("REQUEST_AUDIT_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenReadingAuditLog_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/requests/" + UUID.randomUUID() + "/audit-log")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Очередь оператора (T-052): маршрут пропускает любой запрос с токеном, право
     * {@code REQUEST_READ_ALL} проверяет сервис. Открыто только чтение.
     */
    @Test
    void givenAnyPermission_whenReadingOperatorQueue_thenUrlRulePassesOnlyGetToMethodCheck()
            throws Exception {
        mockMvc.perform(get("/admin/queue").with(jwtWithAuthority("REQUEST_READ_OWN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/queue").with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoCredentials_whenReadingOperatorQueue_thenReturnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/admin/queue").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Мягкое уудаление (T-047): URL-правило пропускает DELETE и POST restore с токеном.
     * право {@code REQUEST_DELETE} проверяет сервис.
     */
    @Test
    void givenAnyPermission_whenSoftDeletingOrRestoring_thenUrlRulePassesToMethodCheck()
            throws Exception {

        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/admin/requests/{id}", id)
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/requests/{id}/restore", id)
                        .with(jwtWithAuthority("REQUEST_READ_ALL")))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenNoCredentials_whenSoftDeletingOrRestoring_thenReturnsUnauthorized()
            throws Exception {

        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/admin/requests/{id}", id).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/requests/{id}/restore", id).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor jwtWithAuthority(String authority) {
        return jwt()
                .jwt(token -> token
                        .subject(USER_ID)
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("permissions", List.of(authority)))
                .authorities(new SimpleGrantedAuthority(authority));
    }
}

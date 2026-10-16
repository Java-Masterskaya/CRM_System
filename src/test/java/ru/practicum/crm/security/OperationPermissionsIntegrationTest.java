package ru.practicum.crm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.crm.security.service.AuthService;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Проверка прав на уровне операций (T-030, DoD #31) — по HTTP, с настоящими токенами: вход,
 * затем запрос с access-токеном. Операция — настройки арендатора: право на неё проверяет
 * {@code @PreAuthorize} на методе сервиса.
 *
 * <p>Роли и права вставляются запросами SQL: так видно, что доступ решают только данные, а
 * название роли ничего не значит.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class OperationPermissionsIntegrationTest extends BaseIntegrationTest {

    private static final String SLUG = "operation-permissions";
    private static final String PASSWORD = "StrongPassword1!";
    private static final String SETTINGS = "/admin/tenant/settings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthTestFixture.TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = authTestFixture.createTenant(SLUG);
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'Europe/Moscow', now(), now())", tenant.id());
    }

    /** DoD: пользователь без нужного права получает 403 независимо от названия его роли. */
    @Test
    void operation_forRoleNamedAdminWithoutPermission_isForbidden() throws Exception {
        UUID admin = roleWith("ADMIN", "REQUEST_READ_ALL", "USER_MANAGE");
        String token = loginAs("admin@example.com", admin);

        call(get(SETTINGS), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** Наоборот: роль с любым названием, но с нужным правом, операцию выполняет. */
    @Test
    void operation_forAnyRoleWithPermission_isAllowedAndNeedsItsOwnPermission()
            throws Exception {
        UUID auditor = roleWith("AUDITOR", "TENANT_SETTINGS_READ");
        String token = loginAs("auditor@example.com", auditor);

        call(get(SETTINGS), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"));
        call(put(SETTINGS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Europe/Paris\"}"), token)
                .andExpect(status().isForbidden());
    }

    /** DoD: добавление права в роль открывает операцию её носителям без изменения кода. */
    @Test
    void operation_afterPermissionAddedToRole_opensOnNextLogin() throws Exception {
        UUID operator = roleWith("OPERATOR", "REQUEST_READ_ALL");
        String before = loginAs("operator@example.com", operator);
        call(get(SETTINGS), before).andExpect(status().isForbidden());

        grant(operator, "TENANT_SETTINGS_READ");
        String after = login("operator@example.com");

        call(get(SETTINGS), after).andExpect(status().isOk());
    }

    /** DoD: клиент, обратившийся к административной операции, получает отказ. */
    @Test
    void administrativeOperation_forClient_isForbidden() throws Exception {
        UUID client = roleWith("CLIENT", "REQUEST_CREATE", "REQUEST_READ_OWN",
                "PROFILE_READ_OWN");
        String token = loginAs("client@example.com", client);

        call(get(SETTINGS), token).andExpect(status().isForbidden());
        call(put(SETTINGS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Europe/Paris\"}"), token)
                .andExpect(status().isForbidden());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String accessToken)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private String loginAs(String email, UUID roleId) {
        AuthTestFixture.TestUser user = authTestFixture.createUser(tenant, email, PASSWORD);
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " VALUES (?, ?, ?)", tenant.id(), user.id(), roleId);
        return login(email);
    }

    private String login(String email) {
        return authService.authenticate(new LoginRequest(SLUG, email, PASSWORD)).accessToken();
    }

    private UUID roleWith(String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO roles (id, tenant_id, code, name, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, now(), now())", roleId, tenant.id(), code,
                "Роль " + code);
        for (String permission : permissions) {
            grant(roleId, permission);
        }
        return roleId;
    }

    /** Право арендатора создаётся один раз и потом только связывается с ролями. */
    private void grant(UUID roleId, String permission) {
        jdbcTemplate.update("INSERT INTO permissions (id, tenant_id, code, created_at, updated_at)"
                + " VALUES (?, ?, ?, now(), now()) ON CONFLICT (tenant_id, code) DO NOTHING",
                UUID.randomUUID(), tenant.id(), permission);
        jdbcTemplate.update("INSERT INTO role_permissions (tenant_id, role_id, permission_id)"
                + " SELECT ?, ?, id FROM permissions WHERE tenant_id = ? AND code = ?",
                tenant.id(), roleId, tenant.id(), permission);
    }
}

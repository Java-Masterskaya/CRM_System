
package ru.practicum.crm.request.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
@TestPropertySource(properties = {
        "app.security.authorization.enabled=true"
})
class ClientRequestCancellationAuthorizationIntegrationTest
        extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String CLIENT_EMAIL = "cancel-owner@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private UUID clientId;
    private String clientToken;
    private UUID requestId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = authTestFixture.createTenant(
                "cancel-" + UUID.randomUUID().toString().replace("-", "")
        );

        jdbcTemplate.query(
                "SELECT seed_tenant_access_defaults(?)",
                rs -> null,
                tenant.id()
        );

        clientId = authTestFixture.createUser(
                tenant,
                CLIENT_EMAIL,
                PASSWORD
        ).id();

        jdbcTemplate.update(
                "INSERT INTO user_roles (tenant_id, user_id, role_id) "
                        + "SELECT tenant_id, ?, id FROM roles "
                        + "WHERE tenant_id = ? AND code = 'CLIENT'",
                clientId,
                tenant.id()
        );

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenant.slug(),
                                "email", CLIENT_EMAIL,
                                "password", PASSWORD
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        clientToken = objectMapper.readTree(loginResponse)
                .get("accessToken")
                .asText();

        requestId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO requests "
                        + "(id, tenant_id, subject, description, status, "
                        + "author_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'Тестовая заявка', "
                        + "'Проверка отмены', 'NEW', ?, now(), now())",
                requestId,
                tenant.id(),
                clientId
        );
    }

    @Test
    void cancelOwnRequest_whenAuthorizationEnabled_cancelsRequest()
            throws Exception {
        mockMvc.perform(post(
                        "/client/requests/{id}/cancel",
                        requestId
                )
                        .header(
                                "Authorization",
                                "Bearer " + clientToken
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String savedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM requests WHERE id = ?",
                String.class,
                requestId
        );

        assertThat(savedStatus).isEqualTo("CANCELLED");

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_status_history "
                        + "WHERE request_id = ?",
                Integer.class,
                requestId
        );

        assertThat(historyCount).isEqualTo(1);
    }
}

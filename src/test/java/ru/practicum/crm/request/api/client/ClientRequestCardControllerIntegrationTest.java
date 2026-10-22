
package ru.practicum.crm.request.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class ClientRequestCardControllerIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String CREATE_URL = "/client/requests";
    private static final String CARD_URL = "/client/requests/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthTestFixture authTestFixture;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestUser user;
    private UUID activeTypeId;
    private String email;
    private String accessToken;

    @BeforeEach
    void createAuthenticatedClient() throws Exception {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);

        tenant = authTestFixture.createTenant("card-" + suffix);
        email = "card-" + suffix + "@example.com";

        user = authTestFixture.createUser(tenant, email, PASSWORD);
        grantClientPermissions(tenant, user);

        activeTypeId = createType(tenant.id());

        accessToken = loginAndGetAccessToken(tenant, email);
    }

    @Test
    void getCard_whenRequestBelongsToCurrentUser_returnsCardWithoutInternalFields()
            throws Exception {
        UUID requestId = createRequest(
                tenant,
                user,
                activeTypeId,
                accessToken,
                "Моя заявка"
        );

        MvcResult result = getCard(accessToken, requestId);

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());

        JsonNode response = readBody(result);

        assertThat(response.path("id").asText())
                .isEqualTo(requestId.toString());
        assertThat(response.path("subject").asText())
                .isEqualTo("Моя заявка");
        assertThat(response.path("status").asText())
                .isEqualTo("NEW");
        assertThat(response.path("priority").asText())
                .isEqualTo("HIGH");
        assertThat(response.path("createdAt").asText()).isNotBlank();
        assertThat(response.path("statusHistory").isArray()).isTrue();

        assertThat(response.has("tenantId")).isFalse();
        assertThat(response.has("authorId")).isFalse();
        assertThat(response.has("assigneeId")).isFalse();
        assertThat(response.has("version")).isFalse();

        assertThat(response.toString())
                .doesNotContain("internalComment")
                .doesNotContain("privateComment");
    }

    @Test
    void getCard_whenRequestBelongsToAnotherUser_returns404()
            throws Exception {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);
        String otherEmail = "other-" + suffix + "@example.com";

        AuthTestFixture.TestUser otherUser =
                authTestFixture.createUser(tenant, otherEmail, PASSWORD);
        grantClientPermissions(tenant, otherUser);

        String otherToken = loginAndGetAccessToken(tenant, otherEmail);

        UUID otherRequestId = createRequest(
                tenant,
                otherUser,
                activeTypeId,
                otherToken,
                "Чужая заявка"
        );

        MvcResult result = getCard(accessToken, otherRequestId);

        assertNotFound(result);
    }

    @Test
    void getCard_whenRequestBelongsToAnotherTenant_returns404()
            throws Exception {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);

        AuthTestFixture.TestTenant otherTenant =
                authTestFixture.createTenant("foreign-" + suffix);
        String otherEmail = "foreign-" + suffix + "@example.com";

        AuthTestFixture.TestUser otherUser =
                authTestFixture.createUser(otherTenant, otherEmail, PASSWORD);
        grantClientPermissions(otherTenant, otherUser);

        UUID otherTypeId = createType(otherTenant.id());
        String otherToken = loginAndGetAccessToken(otherTenant, otherEmail);

        UUID foreignRequestId = createRequest(
                otherTenant,
                otherUser,
                otherTypeId,
                otherToken,
                "Заявка другого арендатора"
        );

        MvcResult result = getCard(accessToken, foreignRequestId);

        assertNotFound(result);
    }

    @Test
    void getCard_whenRequestDoesNotExist_returns404()
            throws Exception {
        MvcResult result = getCard(accessToken, UUID.randomUUID());

        assertNotFound(result);
    }

    @Test
    void getCard_withoutAuthentication_returns401()
            throws Exception {
        MvcResult result = mockMvc.perform(
                        get(CARD_URL + UUID.randomUUID())
                )
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private UUID createRequest(
            AuthTestFixture.TestTenant requestTenant,
            AuthTestFixture.TestUser requestAuthor,
            UUID typeId,
            String token,
            String subject
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("typeId", typeId.toString());
        body.put("subject", subject);
        body.put("description", "Описание для интеграционного теста");

        ObjectNode params = body.putObject("dataParams");
        params.put("periodFrom", "2026-07-01");
        params.put("periodTo", "2026-09-30");

        body.put("priority", RequestPriority.HIGH.name());
        body.put("desiredDueAt", Instant.parse("2026-10-25T15:00:00Z").toString());

        MvcResult result = mockMvc.perform(
                        post(CREATE_URL)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body))
                )
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("Создание тестовой заявки должно пройти успешно")
                .isEqualTo(HttpStatus.CREATED.value());

        JsonNode response = readBody(result);
        UUID requestId = UUID.fromString(response.path("id").asText());

        // Проверяем, что тест создал заявку для ожидаемого автора и арендатора.
        jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM requests
                WHERE id = ?
                  AND tenant_id = ?
                  AND author_id = ?
                """,
                Integer.class,
                requestId,
                requestTenant.id(),
                requestAuthor.id()
        );

        return requestId;
    }

    private MvcResult getCard(String token, UUID requestId) throws Exception {
        return mockMvc.perform(
                        get(CARD_URL + requestId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andReturn();
    }

    private void assertNotFound(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());

        assertThat(readBody(result).path("code").asText())
                .isEqualTo("NOT_FOUND");
    }

    private void grantClientPermissions(
            AuthTestFixture.TestTenant targetTenant,
            AuthTestFixture.TestUser targetUser
    ) {
        authTestFixture.grantPermission(
                targetTenant, targetUser, "REQUEST_CREATE"
        );
        authTestFixture.grantPermission(
                targetTenant, targetUser, "REQUEST_READ_OWN"
        );
    }

    private UUID createType(UUID tenantId) {
        UUID typeId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO request_types (
                    id,
                    tenant_id,
                    name,
                    description,
                    default_priority,
                    active,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                typeId,
                tenantId,
                "Тип карточки " + UUID.randomUUID(),
                "Тип для интеграционных тестов",
                RequestPriority.HIGH.name(),
                true
        );

        return typeId;
    }

    private String loginAndGetAccessToken(
            AuthTestFixture.TestTenant targetTenant,
            String targetEmail
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new LoginPayload(
                                                targetTenant.slug(),
                                                targetEmail,
                                                PASSWORD
                                        )
                                ))
                )
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());

        return readBody(result).path("accessToken").asText();
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private record LoginPayload(
            String tenantSlug,
            String email,
            String password
    ) {
    }
}

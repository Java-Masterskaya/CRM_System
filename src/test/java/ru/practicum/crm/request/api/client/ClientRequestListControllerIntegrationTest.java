package ru.practicum.crm.request.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class ClientRequestListControllerIntegrationTest extends BaseIntegrationTest {

    private static final String ENDPOINT = "/client/requests";
    private static final String PASSWORD = "StrongPassword1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private AuthTestFixture.TestTenant otherTenant;
    private UUID clientId;
    private String clientEmail;
    private String clientToken;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tenant = insertTenant();
        otherTenant = insertTenant();

        clientEmail = email();
        clientId = createClient(tenant, clientEmail);
        clientToken = login(tenant, clientEmail);

        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void list_returnsOnlyCurrentClientRequestsWithinCurrentTenant() throws Exception {

        UUID anotherClientId = createClient(tenant, email());
        insertRequest(
                tenant, anotherClientId, "NEW",
                "Заявка другого клиента", now);

        UUID foreignClientId = createClient(otherTenant, email());
        insertRequest(
                otherTenant, foreignClientId, "NEW",
                "Заявка другого арендатора", now);

        UUID deletedRequest = insertRequest(
                tenant, clientId, "NEW", "Удалённая заявка", now);
        jdbcTemplate.update(
                "UPDATE requests SET deleted = TRUE WHERE id = ?",
                deletedRequest);

        UUID ownRequest = insertRequest(
                tenant, clientId, "NEW", "Моя заявка", now.minusSeconds(1));

        JsonNode response = body(
                "authorId", anotherClientId.toString(),
                "tenantId", otherTenant.id().toString());

        assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.path("content").size()).isEqualTo(1);
        assertThat(response.path("content").get(0).path("id").asText())
                .isEqualTo(ownRequest.toString());
    }

    @Test
    void list_withStatusFilter_returnsOnlyMatchingOwnRequests() throws Exception {

        insertRequest(
                tenant, clientId, "DONE", "Завершённая заявка",
                now.minusSeconds(1));

        UUID anotherClientId = createClient(tenant, email());
        insertRequest(
                tenant, anotherClientId, "NEW",
                "Чужая новая заявка", now.plusSeconds(1));

        UUID newRequest = insertRequest(
                tenant, clientId, "NEW", "Новая заявка", now);

        JsonNode response = body("status", "NEW");

        assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.path("content").get(0).path("id").asText())
                .isEqualTo(newRequest.toString());
        assertThat(response.path("content").get(0).path("status").asText())
                .isEqualTo("NEW");
    }

    @Test
    void list_returnsPageWithTotalAndRequestedSort() throws Exception {
        insertRequest(
                tenant, clientId, "NEW", "Старая заявка",
                now.minusSeconds(30));

        insertRequest(
                tenant, clientId, "DONE", "Средняя заявка",
                now.minusSeconds(15));

        UUID newestId = insertRequest(
                tenant, clientId, "IN_PROGRESS", "Новая заявка",
                now);

        JsonNode firstPage = body(
                "page", "0",
                "size", "1",
                "sort", "createdAt,desc");

        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(1);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.path("content").size()).isEqualTo(1);
        assertThat(firstPage.path("content").get(0).path("id").asText())
                .isEqualTo(newestId.toString());

        JsonNode secondPage = body(
                "page", "1",
                "size", "1",
                "sort", "createdAt,desc");

        assertThat(secondPage.path("page").asInt()).isEqualTo(1);
        assertThat(secondPage.path("totalElements").asLong()).isEqualTo(3);
        assertThat(secondPage.path("content").size()).isEqualTo(1);
    }

    @Test
    void list_whenRequestedSizeExceedsMaximum_clampsSizeToMaximum() throws Exception {
        jdbcTemplate.update(
                """
                INSERT INTO requests (
                    id, tenant_id, subject, description, status,
                    priority, priority_rank, author_id, created_at, updated_at
                )
                SELECT gen_random_uuid(), ?, 'Заявка ' || series.n,
                       'Описание', 'NEW', 'NORMAL', ?, ?,
                       now() - series.n * interval '1 second', now()
                FROM generate_series(1, 105) AS series(n)
                """,
                tenant.id(),
                RequestPriority.NORMAL.getRank(),
                clientId);

        JsonNode response = body(
                "page", "0",
                "size", Integer.toString(PageRequests.MAX_SIZE + 1),
                "sort", "createdAt,desc");

        assertThat(response.path("size").asInt())
                .isEqualTo(PageRequests.MAX_SIZE);
        assertThat(response.path("totalElements").asLong()).isEqualTo(105);
        assertThat(response.path("totalPages").asInt()).isEqualTo(2);
        assertThat(response.path("content").size())
                .isEqualTo(PageRequests.MAX_SIZE);
    }

    @Test
    void list_doesNotExposeInternalRequestFields() throws Exception {
        insertRequest(
                tenant, clientId, "IN_PROGRESS",
                "Заявка с назначенным исполнителем", now);

        MvcResult result = mockMvc.perform(
                        get(ENDPOINT)
                                .header(HttpHeaders.AUTHORIZATION,
                                        "Bearer " + clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].subject").exists())
                .andExpect(jsonPath("$.content[0].status").exists())
                .andReturn();

        JsonNode item = objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .path("content")
                .get(0);

        assertThat(item.has("assigneeId")).isFalse();
        assertThat(item.has("assignedAt")).isFalse();
        assertThat(item.has("authorId")).isFalse();
        assertThat(item.has("version")).isFalse();
        assertThat(item.has("firstResponseOverdue")).isFalse();
        assertThat(item.has("resolutionDueAt")).isFalse();
        assertThat(item.has("internalComments")).isFalse();
        assertThat(item.has("serviceNotes")).isFalse();
    }

    @Test
    void list_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private JsonNode body(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken);

        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray());
    }

    private UUID insertRequest(
            AuthTestFixture.TestTenant requestTenant,
            UUID authorId,
            String requestStatus,
            String subject,
            Instant createdAt
    ) {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO requests (
                    id, tenant_id, subject, description, status,
                    priority, priority_rank, author_id, created_at, updated_at
                ) VALUES (?, ?, ?, 'Тестовое описание', ?, 'NORMAL', ?, ?, ?::timestamptz, now())
                """,
                id,
                requestTenant.id(),
                subject,
                requestStatus,
                RequestPriority.NORMAL.getRank(),
                authorId,
                createdAt.toString());

        return id;
    }

    private UUID createClient(
            AuthTestFixture.TestTenant clientTenant,
            String email
    ) {
        UUID id = authTestFixture.createUser(
                clientTenant, email, PASSWORD).id();

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO user_roles (tenant_id, user_id, role_id)
                SELECT tenant_id, ?, id
                FROM roles
                WHERE tenant_id = ? AND code = 'CLIENT'
                """,
                id,
                clientTenant.id());

        assertThat(inserted).isEqualTo(1);

        return id;
    }

    private AuthTestFixture.TestTenant insertTenant() {
        AuthTestFixture.TestTenant created = authTestFixture.createTenant(
                "client-list-" + UUID.randomUUID().toString().replace("-", ""));

        jdbcTemplate.query(
                "SELECT seed_tenant_access_defaults(?)",
                rs -> null,
                created.id());

        return created;
    }

    private String login(
            AuthTestFixture.TestTenant loginTenant,
            String email
    ) throws Exception {
        String response = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "tenantSlug", loginTenant.slug(),
                                        "email", email,
                                        "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .get("accessToken")
                .asText();
    }

    private static String email() {
        return "client-" + UUID.randomUUID() + "@example.com";
    }
}

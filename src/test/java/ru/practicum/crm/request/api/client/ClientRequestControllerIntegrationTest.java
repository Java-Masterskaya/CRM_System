
package ru.practicum.crm.request.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ClientRequestControllerIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String CREATE_URL = "/client/requests";

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

        tenant = authTestFixture.createTenant("client-" + suffix);
        email = "client-" + suffix + "@example.com";

        user = authTestFixture.createUser(tenant, email, PASSWORD);
        authTestFixture.grantPermission(tenant, user, "REQUEST_CREATE");

        activeTypeId = createType(
                tenant.id(),
                true,
                RequestPriority.HIGH
        );

        accessToken = loginAndGetAccessToken();
    }

    @Test
    void create_whenRequestIsValid_returns201AndUsesServerContext()
            throws Exception {
        ObjectNode request = requestBody(
                activeTypeId,
                "Выгрузка за квартал",
                "Подготовить отчёт за третий квартал.",
                null,
                Instant.parse("2026-10-25T15:00:00Z")
        );

        // Клиент пытается подменить автора, арендатора и стартовый статус.
        request.put("authorId", UUID.randomUUID().toString());
        request.put("tenantId", UUID.randomUUID().toString());
        request.put("status", "DONE");

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());

        JsonNode response = readBody(result);

        assertThat(response.path("id").asText()).isNotBlank();
        assertThat(response.path("typeId").asText())
                .isEqualTo(activeTypeId.toString());
        assertThat(response.path("subject").asText())
                .isEqualTo("Выгрузка за квартал");
        assertThat(response.path("description").asText())
                .isEqualTo("Подготовить отчёт за третий квартал.");
        assertThat(response.path("priority").asText()).isEqualTo("HIGH");
        assertThat(response.path("status").asText()).isEqualTo("NEW");
        assertThat(response.path("dataParams").path("periodFrom").asText())
                .isEqualTo("2026-07-01");
        assertThat(response.path("authorId").isMissingNode()).isTrue();
        assertThat(response.path("tenantId").isMissingNode()).isTrue();

        UUID requestId = UUID.fromString(response.path("id").asText());

        RequestRow saved = findRequest(requestId);

        assertThat(saved.tenantId()).isEqualTo(tenant.id());
        assertThat(saved.authorId()).isEqualTo(user.id());
        assertThat(saved.status()).isEqualTo("NEW");
        assertThat(saved.priority()).isEqualTo("HIGH");

        assertThat(countRequests()).isEqualTo(1);
    }

    @Test
    void create_whenPriorityIsOmitted_usesTypeDefault()
            throws Exception {
        ObjectNode request = requestBody(
                activeTypeId,
                "Заявка без приоритета",
                "Описание заявки.",
                null,
                null
        );

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());
        assertThat(readBody(result).path("priority").asText())
                .isEqualTo("HIGH");
    }

    @Test
    void create_whenSubjectBlankAndDescriptionTooLong_returnsFieldErrors()
            throws Exception {
        ObjectNode request = requestBody(
                activeTypeId,
                "   ",
                "x".repeat(10_001),
                null,
                null
        );

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        JsonNode response = readBody(result);
        assertThat(response.path("code").asText())
                .isEqualTo("VALIDATION_FAILED");

        assertThat(response.path("errors").toString())
                .contains("#/subject")
                .contains("#/description");

        assertThat(countRequests()).isZero();
    }

    @Test
    void create_whenRequestTypeDoesNotExist_returns404WithoutSaving()
            throws Exception {
        ObjectNode request = requestBody(
                UUID.randomUUID(),
                "Неизвестный тип",
                "Описание заявки.",
                RequestPriority.NORMAL,
                null
        );

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(result).path("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(countRequests()).isZero();
    }

    @Test
    void create_whenRequestTypeIsInactive_returnsValidationErrorWithoutSaving()
            throws Exception {
        UUID inactiveTypeId = createType(
                tenant.id(),
                false,
                RequestPriority.NORMAL
        );

        ObjectNode request = requestBody(
                inactiveTypeId,
                "Тип отключён",
                "Описание заявки.",
                null,
                null
        );

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        JsonNode response = readBody(result);
        assertThat(response.path("code").asText())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(response.path("errors").toString())
                .contains("#/typeId");

        assertThat(countRequests()).isZero();
    }

    @Test
    void create_whenTypeBelongsToAnotherTenant_returns404WithoutSaving()
            throws Exception {
        AuthTestFixture.TestTenant foreignTenant =
                authTestFixture.createTenant(
                        "foreign-" + UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 12)
                );

        UUID foreignTypeId = createType(
                foreignTenant.id(),
                true,
                RequestPriority.NORMAL
        );

        ObjectNode request = requestBody(
                foreignTypeId,
                "Чужой тип",
                "Описание заявки.",
                null,
                null
        );

        MvcResult result = submit(
                UUID.randomUUID().toString(),
                objectMapper.writeValueAsString(request)
        );

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(result).path("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(countRequests()).isZero();
    }

    @Test
    void create_withoutAuthentication_returns401()
            throws Exception {
        ObjectNode request = requestBody(
                activeTypeId,
                "Без авторизации",
                "Описание заявки.",
                null,
                null
        );

        MvcResult result = mockMvc.perform(
                        post(CREATE_URL)
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(result).path("code").asText())
                .isEqualTo("UNAUTHENTICATED");
        assertThat(countRequests()).isZero();
    }

    @Test
    void create_whenIdempotencyKeyAndPayloadAreRepeated_returnsOriginalResponse()
            throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(
                requestBody(
                        activeTypeId,
                        "Идемпотентная заявка",
                        "Описание.",
                        RequestPriority.NORMAL,
                        null
                )
        );

        MvcResult first = submit(key, payload);
        MvcResult second = submit(key, payload);

        assertThat(first.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());
        assertThat(second.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());

        JsonNode firstBody = readBody(first);
        JsonNode secondBody = readBody(second);

        assertThat(secondBody.path("id").asText())
                .isEqualTo(firstBody.path("id").asText());
        assertThat(secondBody.path("status").asText()).isEqualTo("NEW");
        assertThat(countRequests()).isEqualTo(1);
        assertThat(countIdempotencyRecords(key)).isEqualTo(1);
    }

    @Test
    void create_whenIdempotencyKeyIsReusedWithDifferentPayload_returns409()
            throws Exception {
        String key = UUID.randomUUID().toString();

        String firstPayload = objectMapper.writeValueAsString(
                requestBody(
                        activeTypeId,
                        "Первая заявка",
                        "Описание.",
                        RequestPriority.NORMAL,
                        null
                )
        );

        String secondPayload = objectMapper.writeValueAsString(
                requestBody(
                        activeTypeId,
                        "Другая заявка",
                        "Описание.",
                        RequestPriority.NORMAL,
                        null
                )
        );

        MvcResult first = submit(key, firstPayload);
        MvcResult second = submit(key, secondPayload);

        assertThat(first.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());
        assertThat(second.getResponse().getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());

        assertThat(readBody(second).path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(countRequests()).isEqualTo(1);
    }

    @Test
    void create_concurrentlyWithSameKey_createsOneRequest()
            throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(
                requestBody(
                        activeTypeId,
                        "Параллельное создание",
                        "Описание.",
                        RequestPriority.NORMAL,
                        null
                )
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> first = executor.submit(
                    () -> submitAfterBarrier(key, payload, ready, start)
            );
            Future<MvcResult> second = executor.submit(
                    () -> submitAfterBarrier(key, payload, ready, start)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CREATED.value());
            assertThat(secondResult.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CREATED.value());

            assertThat(readBody(firstResult).path("id").asText())
                    .isEqualTo(readBody(secondResult).path("id").asText());

            assertThat(countRequests()).isEqualTo(1);
            assertThat(countIdempotencyRecords(key)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MvcResult submit(String key, String payload) throws Exception {
        return mockMvc.perform(
                        post(CREATE_URL)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + accessToken
                                )
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                )
                .andReturn();
    }

    private MvcResult submitAfterBarrier(
            String key,
            String payload,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "Concurrent requests did not start in time"
            );
        }

        return submit(key, payload);
    }

    private String loginAndGetAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new LoginPayload(
                                                tenant.slug(),
                                                email,
                                                PASSWORD
                                        )
                                ))
                )
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());

        return readBody(result).path("accessToken").asText();
    }

    private UUID createType(
            UUID tenantId,
            boolean active,
            RequestPriority defaultPriority
    ) {
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
                "Тестовый тип " + UUID.randomUUID(),
                "Тип для интеграционных тестов",
                defaultPriority == null ? null : defaultPriority.name(),
                active
        );

        return typeId;
    }

    private ObjectNode requestBody(
            UUID typeId,
            String subject,
            String description,
            RequestPriority priority,
            Instant desiredDueAt
    ) {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("typeId", typeId.toString());
        body.put("subject", subject);
        body.put("description", description);

        ObjectNode params = body.putObject("dataParams");
        params.put("periodFrom", "2026-07-01");
        params.put("periodTo", "2026-09-30");

        if (priority == null) {
            body.putNull("priority");
        } else {
            body.put("priority", priority.name());
        }

        if (desiredDueAt != null) {
            body.put("desiredDueAt", desiredDueAt.toString());
        }

        return body;
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private RequestRow findRequest(UUID requestId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT tenant_id, author_id, status, priority
                FROM requests
                WHERE id = ?
                """,
                (rs, rowNum) -> new RequestRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("priority")
                ),
                requestId
        );
    }

    private int countRequests() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM requests WHERE tenant_id = ?",
                Integer.class,
                tenant.id()
        );

        return count == null ? 0 : count;
    }

    private int countIdempotencyRecords(String key) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND operation = 'CLIENT_REQUEST_CREATE'
                  AND idempotency_key = ?
                """,
                Integer.class,
                tenant.id(),
                user.id(),
                key
        );

        return count == null ? 0 : count;
    }

    private record LoginPayload(
            String tenantSlug,
            String email,
            String password
    ) {
    }

    private record RequestRow(
            UUID tenantId,
            UUID authorId,
            String status,
            String priority
    ) {
    }
}

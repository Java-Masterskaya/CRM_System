package ru.practicum.crm.security.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.api.dto.LoginRequest;
import ru.practicum.testsupport.AuthTestFixture;

@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class LoginRateLimitIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "StrongPassword1!";
    private static final String WRONG_PASSWORD = "WrongPassword2!";
    private static final int MAX_ATTEMPTS = 5;
    private static final long WAIT_FOR_EXPIRATION_MILLIS = 2_200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthTestFixture authTestFixture;

    @DynamicPropertySource
    static void loginRateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "crm.security.login-rate-limit.max-attempts",
                () -> Integer.toString(MAX_ATTEMPTS)
        );
        registry.add(
                "crm.security.login-rate-limit.window",
                () -> "2s"
        );
        registry.add(
                "crm.security.login-rate-limit.block-duration",
                () -> "2s"
        );
    }

    @Test
    void login_afterMaxFailedAttempts_returns429WithRetryAfter()
            throws Exception {
        Account account = createAccount();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(account);
        }

        assertRateLimited(account);
    }

    @Test
    void login_whenBlockExpires_acceptsCredentialsAgain()
            throws Exception {
        Account account = createAccount();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(account);
        }

        assertRateLimited(account);

        TimeUnit.MILLISECONDS.sleep(WAIT_FOR_EXPIRATION_MILLIS);

        MvcResult result = login(account, PASSWORD);

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());

        JsonNode body = readBody(result);

        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("refreshToken").asText()).isNotBlank();
    }

    @Test
    void login_afterSuccessfulAuthentication_resetsFailureCounter()
            throws Exception {
        Account account = createAccount();

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            assertInvalidCredentials(account);
        }

        MvcResult success = login(account, PASSWORD);

        assertThat(success.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(account);
        }

        assertRateLimited(account);
    }

    @Test
    void login_whenAttemptWindowExpires_resetsFailureCounter()
            throws Exception {
        Account account = createAccount();

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            assertInvalidCredentials(account);
        }

        TimeUnit.MILLISECONDS.sleep(WAIT_FOR_EXPIRATION_MILLIS);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(account);
        }

        assertRateLimited(account);
    }

    @Test
    void login_limitForOneAccount_doesNotBlockAnotherAccount()
            throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantSlug = "limit-" + suffix;

        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant(tenantSlug);

        Account firstAccount = new Account(
                tenantSlug,
                "first-" + suffix + "@example.com"
        );

        Account secondAccount = new Account(
                tenantSlug,
                "second-" + suffix + "@example.com"
        );

        authTestFixture.createUser(
                tenant,
                firstAccount.email(),
                PASSWORD
        );

        authTestFixture.createUser(
                tenant,
                secondAccount.email(),
                PASSWORD
        );

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(firstAccount);
        }

        assertRateLimited(firstAccount);

        MvcResult secondUserResult = login(secondAccount, PASSWORD);

        assertThat(secondUserResult.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void login_rateLimitResponseDoesNotRevealWhetherAccountExists()
            throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantSlug = "limit-" + suffix;

        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant(tenantSlug);

        Account existingAccount = new Account(
                tenantSlug,
                "existing-" + suffix + "@example.com"
        );

        Account unknownAccount = new Account(
                tenantSlug,
                "unknown-" + suffix + "@example.com"
        );

        authTestFixture.createUser(
                tenant,
                existingAccount.email(),
                PASSWORD
        );

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(existingAccount);
        }

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertInvalidCredentials(unknownAccount);
        }

        MvcResult existingResult = login(existingAccount, PASSWORD);
        MvcResult unknownResult = login(unknownAccount, PASSWORD);

        assertRateLimitedResult(existingResult);
        assertRateLimitedResult(unknownResult);

        JsonNode existingBody = readBody(existingResult);
        JsonNode unknownBody = readBody(unknownResult);

        assertThat(existingBody.path("code").asText())
                .isEqualTo(unknownBody.path("code").asText());

        assertThat(existingBody.path("title").asText())
                .isEqualTo(unknownBody.path("title").asText());

        assertThat(existingBody.path("detail").asText())
                .isEqualTo(unknownBody.path("detail").asText());
    }

    private Account createAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantSlug = "limit-" + suffix;
        String email = "user-" + suffix + "@example.com";

        AuthTestFixture.TestTenant tenant =
                authTestFixture.createTenant(tenantSlug);

        authTestFixture.createUser(tenant, email, PASSWORD);

        return new Account(tenantSlug, email);
    }

    private MvcResult login(Account account, String password)
            throws Exception {
        LoginRequest request = new LoginRequest(
                account.tenantSlug(),
                account.email(),
                password
        );

        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
        ).andReturn();
    }

    private void assertInvalidCredentials(Account account) throws Exception {
        MvcResult result = login(account, WRONG_PASSWORD);

        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        assertThat(readBody(result).path("code").asText())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    private void assertRateLimited(Account account) throws Exception {
        assertRateLimitedResult(login(account, PASSWORD));
    }

    private void assertRateLimitedResult(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(readBody(result).path("code").asText())
                .isEqualTo("RATE_LIMITED");

        String retryAfter =
                result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(retryAfter).isNotBlank();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString()
        );
    }

    private record Account(String tenantSlug, String email) {
    }
}

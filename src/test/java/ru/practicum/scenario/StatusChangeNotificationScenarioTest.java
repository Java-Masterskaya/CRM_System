package ru.practicum.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.service.OutboxProcessor;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Сквозной сценарий T-070: оператор меняет статус заявки по HTTP, обработчик очереди отправляет
 * письмо, почтовый сервер внутри теста (GreenMail) его принимает.
 *
 * <p>Сценарий проходит через три модуля — заявки, очередь и уведомления, — поэтому лежит вне
 * {@code ru.practicum.crm}: правила ArchUnit разрешают модулю обращаться к другому только через
 * его {@code api}, а здесь нужен сам обработчик очереди. Его расписание в тестах выключено, и
 * тест запускает проход сам.
 */
@AutoConfigureMockMvc
@Import(AuthTestFixture.class)
class StatusChangeNotificationScenarioTest extends BaseIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final String PASSWORD = "StrongPassword1!";
    private static final String OPERATOR = "operator@example.com";
    private static final String CLIENT = "client@example.com";

    private static final GreenMail MAIL_SERVER = startMailServer();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthTestFixture authTestFixture;

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthTestFixture.TestTenant tenant;
    private UUID clientId;
    private String operatorToken;

    @DynamicPropertySource
    static void mailServerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> HOST);
        registry.add("spring.mail.port", () -> MAIL_SERVER.getSmtp().getPort());
    }

    @AfterAll
    static void stopMailServer() {
        MAIL_SERVER.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        MAIL_SERVER.purgeEmailFromAllMailboxes();
        tenant = authTestFixture.createTenant(
                "scenario-" + UUID.randomUUID().toString().replace("-", ""));
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, tenant.id());
        insertUser(OPERATOR, "OPERATOR");
        clientId = insertUser(CLIENT, "CLIENT");
        jdbcTemplate.update("UPDATE users SET name = 'Анна Петрова' WHERE id = ?", clientId);
        operatorToken = login(OPERATOR);
    }

    /** DoD: после смены статуса автор заявки получает письмо с прежним и новым статусом. */
    @Test
    void statusChange_deliversLetterWithOldAndNewStatusToAuthor() throws Exception {
        UUID id = insertRequest("NEW");

        changeStatus(id, "CONTACTED");
        processor.processBatch();

        MimeMessage[] received = MAIL_SERVER.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Address::toString)
                .containsExactly(CLIENT);
        assertThat(received[0].getSubject())
                .isEqualTo("Статус заявки «Выгрузка за квартал» изменён");
        assertThat((String) received[0].getContent())
                .contains("Анна Петрова", "Получена", "Детали уточнены", id.toString());
    }

    /**
     * DoD: при недоступной почте смена статуса всё равно сохраняется, событие остаётся в очереди и
     * уходит после восстановления. Почта «недоступна» — отправитель смотрит на порт, где никто не
     * слушает; время следующей попытки сдвигается вручную, чтобы не ждать задержку повтора.
     */
    @Test
    void statusChange_whenMailIsDown_isSavedAndLetterLeavesAfterRecovery() throws Exception {
        UUID id = insertRequest("NEW");
        int workingPort = mailSender.getPort();
        mailSender.setPort(closedPort());
        try {
            changeStatus(id, "CONTACTED");
            processor.processBatch();
        } finally {
            mailSender.setPort(workingPort);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM requests WHERE id = ?",
                String.class, id)).isEqualTo("CONTACTED");
        assertThat(MAIL_SERVER.getReceivedMessages()).isEmpty();
        assertThat(letterEvent(id)).containsEntry("status", "NEW").containsEntry("attempts", 1);

        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now()"
                + " WHERE aggregate_id = ?", id);
        processor.processBatch();

        assertThat(MAIL_SERVER.getReceivedMessages()).hasSize(1);
        assertThat(letterEvent(id)).containsEntry("status", "SENT");
    }

    private Map<String, Object> letterEvent(UUID requestId) {
        return jdbcTemplate.queryForMap("SELECT status, attempts FROM outbox_events"
                + " WHERE event_type = 'EMAIL_NOTIFICATION' AND aggregate_id = ?", requestId);
    }

    private void changeStatus(UUID requestId, String target) throws Exception {
        mockMvc.perform(put("/admin/requests/{id}/status", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", target, "version", 0))))
                .andExpect(status().isOk());
    }

    private UUID insertRequest(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, created_at, updated_at) VALUES (?, ?, 'Выгрузка за квартал',"
                + " 'Нужен отчёт', ?, ?, now(), now())", id, tenant.id(), status, clientId);
        return id;
    }

    private UUID insertUser(String email, String roleCode) {
        UUID id = authTestFixture.createUser(tenant, email, PASSWORD).id();
        jdbcTemplate.update("INSERT INTO user_roles (tenant_id, user_id, role_id)"
                + " SELECT tenant_id, ?, id FROM roles WHERE tenant_id = ? AND code = ?",
                id, tenant.id(), roleCode);
        return id;
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenant.slug(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return socket.getLocalPort();
        }
    }

    private static GreenMail startMailServer() {
        GreenMail server = new GreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_SMTP));
        server.start();
        return server;
    }
}

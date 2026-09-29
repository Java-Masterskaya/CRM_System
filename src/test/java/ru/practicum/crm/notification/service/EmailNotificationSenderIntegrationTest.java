package ru.practicum.crm.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.notification.channel.EmailChannel;
import ru.practicum.crm.outbox.api.OutboxDeliveryException;
import ru.practicum.crm.outbox.api.OutboxMessage;

/**
 * Отправитель в собранном приложении: настоящая база с миграциями, почтовый отправитель из
 * настроек Spring Boot и почтовый сервер внутри теста (GreenMail).
 *
 * <p>Отправитель вызывается напрямую, как его вызывает обработчик очереди. Как обработчик
 * записывает код неудачи в событие, проверяют тесты самого обработчика: зависеть от его
 * внутренних классов пакету {@code notification} нельзя.
 */
class EmailNotificationSenderIntegrationTest extends BaseIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final String RECIPIENT = "client@example.com";
    private static final String RECIPIENT_NAME = "Анна Петрова";
    private static final String SUBJECT = "Не работает выгрузка";
    private static final EmailNotification NOTIFICATION = new EmailNotification(RECIPIENT,
            NotificationType.REQUEST_CREATED, Map.of("recipientName", RECIPIENT_NAME,
                    "requestId", "42", "requestSubject", SUBJECT));

    private static final GreenMail MAIL_SERVER = startMailServer();

    @Autowired
    private EmailNotificationSender sender;

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID eventId;

    @DynamicPropertySource
    static void mailServerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> HOST);
        registry.add("spring.mail.port", () -> MAIL_SERVER.getSmtp().getPort());
    }

    @AfterAll
    static void stopMailServer() {
        MAIL_SERVER.stop();
    }

    /** Строка события в очереди нужна только для внешнего ключа уведомления. */
    @BeforeEach
    void setUp() throws Exception {
        MAIL_SERVER.purgeEmailFromAllMailboxes();
        tenantId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
        jdbcTemplate.update("INSERT INTO outbox_events (id, tenant_id, event_type, payload,"
                + " status, attempts, next_attempt_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, '{}', 'IN_PROGRESS', 1, now(), now(), now())",
                eventId, tenantId, EmailNotification.EVENT_TYPE);
    }

    @Test
    void send_whenMailServerAccepts_deliversLetterAndRecordsNotificationSent() throws Exception {
        sender.send(attempt(1));

        MimeMessage[] received = MAIL_SERVER.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Заявка «" + SUBJECT + "» принята");
        assertThat(received[0].getAllRecipients()).extracting(Address::toString)
                .containsExactly(RECIPIENT);
        assertThat(notificationRow()).containsEntry("status", "SENT")
                .containsEntry("attempts", 1)
                .containsEntry("tenant_id", tenantId)
                .containsEntry("notification_type", "REQUEST_CREATED")
                .containsEntry("recipient_email", RECIPIENT)
                .containsEntry("has_sent_at", true);
    }

    @Test
    void send_whenMailServerUnreachable_recordsFailureAndRetryUpdatesSameRow() throws Exception {
        assertThatThrownBy(this::sendWithUnreachableMailServer)
                .isInstanceOfSatisfying(OutboxDeliveryException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(EmailChannel.SERVER_UNAVAILABLE));
        assertThat(MAIL_SERVER.getReceivedMessages()).isEmpty();
        assertThat(notificationRow()).containsEntry("status", "FAILED")
                .containsEntry("attempts", 1)
                .containsEntry("last_error_code", EmailChannel.SERVER_UNAVAILABLE);

        sender.send(attempt(2));

        assertThat(MAIL_SERVER.getReceivedMessages()).hasSize(1);
        assertThat(notificationRow()).containsEntry("status", "SENT")
                .containsEntry("attempts", 2)
                .containsEntry("last_error_code", null);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void deleteOutboxEvent_afterDelivery_keepsNotification() {
        sender.send(attempt(1));

        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", eventId);

        assertThat(jdbcTemplate.queryForMap("SELECT status, outbox_event_id FROM notifications"
                + " WHERE recipient_email = ?", RECIPIENT))
                .containsEntry("status", "SENT")
                .containsEntry("outbox_event_id", null);
    }

    @Test
    void send_whenLetterDelivered_logsNeitherAddressNorNameNorSubject() {
        Logger logger = (Logger) LoggerFactory.getLogger("ru.practicum.crm");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            sender.send(attempt(1));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(eventId.toString()))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(RECIPIENT, RECIPIENT_NAME, SUBJECT));
    }

    private OutboxMessage attempt(int number) {
        return new OutboxMessage(eventId, tenantId, EmailNotification.EVENT_TYPE,
                NOTIFICATION.toPayload(), number);
    }

    /** Первая попытка, пока почтовый сервер «недоступен»: порт, на котором никто не слушает. */
    private void sendWithUnreachableMailServer() throws IOException {
        int workingPort = mailSender.getPort();
        mailSender.setPort(closedPort());
        try {
            sender.send(attempt(1));
        } finally {
            mailSender.setPort(workingPort);
        }
    }

    private Map<String, Object> notificationRow() {
        return jdbcTemplate.queryForMap("SELECT tenant_id, status, attempts, last_error_code,"
                + " notification_type, recipient_email, sent_at IS NOT NULL AS has_sent_at"
                + " FROM notifications WHERE outbox_event_id = ?", eventId);
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

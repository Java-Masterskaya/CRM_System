package ru.practicum.crm.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionOperations;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.notification.channel.EmailChannel;
import ru.practicum.crm.notification.channel.EmailSendResult;
import ru.practicum.crm.notification.config.NotificationProperties;
import ru.practicum.crm.notification.domain.Notification;
import ru.practicum.crm.notification.domain.NotificationStatus;
import ru.practicum.crm.notification.repository.NotificationRepository;
import ru.practicum.crm.notification.template.MailTemplateRenderer;
import ru.practicum.crm.notification.template.RenderedMail;
import ru.practicum.crm.outbox.api.OutboxDeliveryException;
import ru.practicum.crm.outbox.api.OutboxMessage;

/**
 * Отправитель без базы и сети: уведомления хранятся в памяти, канал — заглушка. Шаблоны
 * писем настоящие.
 */
class EmailNotificationSenderTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String RECIPIENT = "client@example.com";
    private static final EmailNotification NOTIFICATION = new EmailNotification(RECIPIENT,
            NotificationType.REQUEST_CREATED, Map.of("recipientName", "Анна Петрова",
                    "requestId", "42", "requestSubject", "Не работает выгрузка"));
    private static final EmailSendResult SERVER_DOWN = EmailSendResult.failure(
            EmailChannel.SERVER_UNAVAILABLE, "Почтовый сервер недоступен или не ответил вовремя");

    private final StubChannel channel = new StubChannel();
    private final InMemoryNotifications notifications = new InMemoryNotifications();
    private final EmailNotificationSender sender = new EmailNotificationSender(
            new MailTemplateRenderer(), channel, notifications,
            TransactionOperations.withoutTransaction());

    @Test
    void send_whenLetterAccepted_sendsRenderedLetterAndRecordsNotificationSent() {
        sender.send(message(1, NOTIFICATION.toPayload()));

        assertThat(channel.recipients).containsExactly(RECIPIENT);
        assertThat(channel.letters).singleElement().extracting(RenderedMail::subject)
                .isEqualTo("Заявка «Не работает выгрузка» принята");
        Notification notification = notifications.only();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getSentAt()).isNotNull();
        assertThat(notification.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(notification.getOutboxEventId()).isEqualTo(EVENT_ID);
        assertThat(notification.getType()).isEqualTo(NotificationType.REQUEST_CREATED);
        assertThat(notification.getRecipientEmail()).isEqualTo(RECIPIENT);
    }

    @Test
    void send_whenServerUnavailable_recordsFailureAndReportsItsCodeToQueue() {
        channel.nextResult = SERVER_DOWN;

        assertThatThrownBy(() -> sender.send(message(1, NOTIFICATION.toPayload())))
                .isInstanceOfSatisfying(OutboxDeliveryException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(EmailChannel.SERVER_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain(RECIPIENT);
                });
        assertThat(notifications.only().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notifications.only().getLastErrorCode())
                .isEqualTo(EmailChannel.SERVER_UNAVAILABLE);
    }

    @Test
    void send_whenRetriedAfterFailure_updatesSameNotification() {
        channel.nextResult = SERVER_DOWN;
        assertThatThrownBy(() -> sender.send(message(1, NOTIFICATION.toPayload())))
                .isInstanceOf(OutboxDeliveryException.class);
        channel.nextResult = EmailSendResult.success();

        sender.send(message(2, NOTIFICATION.toPayload()));

        assertThat(notifications.byEvent).hasSize(1);
        assertThat(notifications.only().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notifications.only().getAttempts()).isEqualTo(2);
    }

    @Test
    void send_whenValueForTemplateIsMissing_recordsTemplateFailureWithoutSending() {
        Map<String, Object> payload = new EmailNotification(RECIPIENT,
                NotificationType.REQUEST_CREATED, Map.of("recipientName", "Анна Петрова"))
                .toPayload();

        assertThatThrownBy(() -> sender.send(message(1, payload)))
                .isInstanceOfSatisfying(OutboxDeliveryException.class, ex -> assertThat(
                        ex.getCode()).isEqualTo(EmailNotificationSender.TEMPLATE_FAILED));
        assertThat(channel.recipients).isEmpty();
        assertThat(notifications.only().getLastErrorCode())
                .isEqualTo(EmailNotificationSender.TEMPLATE_FAILED);
    }

    @Test
    void send_whenPayloadIsNotNotification_reportsInvalidPayloadAndRecordsNothing() {
        assertThatThrownBy(() -> sender.send(message(1, Map.of("n", 1))))
                .isInstanceOfSatisfying(OutboxDeliveryException.class, ex -> assertThat(
                        ex.getCode()).isEqualTo(EmailNotificationSender.INVALID_PAYLOAD));
        assertThat(channel.recipients).isEmpty();
        assertThat(notifications.byEvent).isEmpty();
    }

    @Test
    void send_whenLetterAccepted_logsNeitherAddressNorNameNorSubject() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailNotificationSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            sender.send(message(1, NOTIFICATION.toPayload()));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString()
                .contains(EVENT_ID.toString())
                .doesNotContain(RECIPIENT, "Анна Петрова", "Не работает выгрузка");
    }

    private static OutboxMessage message(int attempt, Map<String, Object> payload) {
        return new OutboxMessage(EVENT_ID, TENANT_ID, EmailNotification.EVENT_TYPE, payload,
                attempt);
    }

    /** Канал без сети: запоминает, что ему дали, и отвечает заданным результатом. */
    static class StubChannel extends EmailChannel {

        final List<String> recipients = new ArrayList<>();
        final List<RenderedMail> letters = new ArrayList<>();
        EmailSendResult nextResult = EmailSendResult.success();

        StubChannel() {
            super(new JavaMailSenderImpl(), new NotificationProperties("noreply@crm.test"));
        }

        @Override
        public EmailSendResult send(String recipient, RenderedMail mail) {
            recipients.add(recipient);
            letters.add(mail);
            return nextResult;
        }
    }

    /** Уведомления в памяти: по одному на событие очереди, как в таблице. */
    static class InMemoryNotifications implements NotificationRepository {

        final Map<UUID, Notification> byEvent = new LinkedHashMap<>();

        @Override
        public <S extends Notification> S save(S notification) {
            byEvent.put(notification.getOutboxEventId(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findByTenantIdAndOutboxEventId(UUID tenantId,
                UUID outboxEventId) {
            return Optional.ofNullable(byEvent.get(outboxEventId))
                    .filter(notification -> notification.getTenantId().equals(tenantId));
        }

        Notification only() {
            assertThat(byEvent).hasSize(1);
            return byEvent.values().iterator().next();
        }
    }
}

package ru.practicum.crm.notification.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.channel.EmailChannel;
import ru.practicum.crm.notification.channel.EmailSendResult;
import ru.practicum.crm.notification.domain.Notification;
import ru.practicum.crm.notification.repository.NotificationRepository;
import ru.practicum.crm.notification.template.MailTemplateException;
import ru.practicum.crm.notification.template.MailTemplateRenderer;
import ru.practicum.crm.notification.template.RenderedMail;
import ru.practicum.crm.outbox.api.OutboxDeliveryException;
import ru.practicum.crm.outbox.api.OutboxEventSender;
import ru.practicum.crm.outbox.api.OutboxMessage;

/**
 * Доставляет письма-уведомления из очереди исходящих событий (T-069).
 *
 * <p>Попытка: разобрать событие, собрать письмо по шаблону, отдать его почтовому серверу и
 * записать результат в уведомление. Неудача сообщается очереди исключением
 * {@link OutboxDeliveryException} с кодом причины — повторять ли, решает очередь.
 *
 * <p>Результат записывается отдельной короткой транзакцией уже после отправки: медленный
 * почтовый сервер не держит соединение с базой. Если приложение упадёт между отправкой и
 * записью, очередь отправит письмо ещё раз (гарантия «хотя бы один раз»), а уведомление
 * найдётся по идентификатору события и обновится, а не задвоится.
 *
 * <p>В лог пишутся только идентификатор события, тип уведомления и номер попытки: адрес, тема
 * и текст письма — персональные данные.
 */
@Component
public class EmailNotificationSender implements OutboxEventSender {

    public static final String INVALID_PAYLOAD = "NOTIFICATION_INVALID_PAYLOAD";
    public static final String TEMPLATE_FAILED = "NOTIFICATION_TEMPLATE_FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final MailTemplateRenderer renderer;
    private final EmailChannel channel;
    private final NotificationRepository repository;
    private final TransactionOperations transactions;

    public EmailNotificationSender(MailTemplateRenderer renderer, EmailChannel channel,
            NotificationRepository repository, TransactionOperations transactions) {
        this.renderer = renderer;
        this.channel = channel;
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    public String eventType() {
        return EmailNotification.EVENT_TYPE;
    }

    @Override
    public void send(OutboxMessage message) {
        EmailNotification notification = parse(message);
        EmailSendResult result = deliver(notification);
        record(message, notification, result);
        if (!result.sent()) {
            throw new OutboxDeliveryException(result.failureCode(), result.failureMessage());
        }
        LOG.info("Уведомление {} по событию {} отправлено, попытка {}", notification.type(),
                message.id(), message.attempt());
    }

    /** Без адресата и типа уведомление записать нельзя — очередь получает только код. */
    private static EmailNotification parse(OutboxMessage message) {
        try {
            return EmailNotification.fromPayload(message.payload());
        } catch (IllegalArgumentException ex) {
            throw new OutboxDeliveryException(INVALID_PAYLOAD,
                    "Полезная нагрузка события не описывает письмо-уведомление");
        }
    }

    private EmailSendResult deliver(EmailNotification notification) {
        RenderedMail mail;
        try {
            mail = renderer.render(notification.type(), notification.variables());
        } catch (MailTemplateException ex) {
            return EmailSendResult.failure(TEMPLATE_FAILED,
                    "Письмо не собрано: нет шаблона или не хватает данных для него");
        }
        return channel.send(notification.recipientEmail(), mail);
    }

    private void record(OutboxMessage message, EmailNotification notification,
            EmailSendResult result) {
        transactions.executeWithoutResult(status -> {
            Notification entry = repository
                    .findByTenantIdAndOutboxEventId(message.tenantId(), message.id())
                    .orElseGet(() -> new Notification(message.tenantId(), message.id(),
                            notification.type(), notification.recipientEmail()));
            if (result.sent()) {
                entry.markSent(message.attempt(), Instant.now());
            } else {
                entry.markFailed(message.attempt(), result.failureCode());
            }
            repository.save(entry);
        });
    }
}

package ru.practicum.crm.request.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Письмо автору заявки о смене её статуса (T-070, ТЗ §6.5): было и стало.
 *
 * <p>Письмо записывается в outbox в транзакции самой смены статуса, а отправляет его фоновый
 * обработчик. Поэтому недоступная почта смену статуса не останавливает: событие ждёт в очереди и
 * уходит после восстановления. И наоборот — откатилась смена статуса, письма тоже нет.
 *
 * <p>В письме только номер, тема и названия статусов для клиента. Комментарий к переходу туда не
 * попадает: его пишет оператор для команды.
 */
@Component
public class RequestStatusNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(RequestStatusNotifier.class);

    private final UserService users;
    private final OutboxPublisher outbox;

    public RequestStatusNotifier(UserService users, OutboxPublisher outbox) {
        this.users = users;
        this.outbox = outbox;
    }

    /**
     * Записывает письмо автору о переходе заявки из {@code from} в её текущий статус. Автор
     * заблокирован или удалён — письма нет, смена статуса от этого не зависит.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Request request, RequestStatus from) {
        Optional<UserContactDto> author =
                users.findActiveContact(request.getTenantId(), request.getAuthorId());
        if (author.isEmpty()) {
            LOG.info("Письмо о смене статуса не записано: автор заявки {} не может работать"
                    + " в системе", value("requestId", request.getId()));
            return;
        }
        Map<String, String> variables = Map.of(
                "recipientName", author.get().displayName(),
                "requestId", request.getId().toString(),
                "requestSubject", request.getSubject(),
                "oldStatus", from.title(),
                "newStatus", request.getStatus().title());
        outbox.publish(request.getTenantId(), EmailNotification.EVENT_TYPE, Request.AGGREGATE_TYPE,
                request.getId(), new EmailNotification(author.get().email(),
                        NotificationType.REQUEST_STATUS_CHANGED, variables).toPayload());
    }
}

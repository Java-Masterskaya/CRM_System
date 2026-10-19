package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Письмо о смене статуса без базы: кому, что в нём и когда его нет. Одну транзакцию со сменой
 * статуса и доставку проверяют интеграционные тесты.
 */
class RequestStatusNotifierTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();

    private final UserService users = mock(UserService.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final RequestStatusNotifier notifier = new RequestStatusNotifier(users, outbox);

    /**
     * DoD: автор получает письмо с прежним и новым статусом — названиями для клиента. Событие
     * ссылается на заявку; данных для шаблона ровно столько, сколько он требует.
     */
    @Test
    void statusChanged_writesLetterToAuthorWithOldAndNewStatus() {
        Request request = request(RequestStatus.ON_HOLD);
        authorIs(new UserContactDto(AUTHOR_ID, "anna@example.com", "Анна"));

        notifier.statusChanged(request, RequestStatus.IN_PROGRESS);

        EmailNotification letter = writtenLetter(request);
        assertThat(letter.recipientEmail()).isEqualTo("anna@example.com");
        assertThat(letter.type()).isEqualTo(NotificationType.REQUEST_STATUS_CHANGED);
        assertThat(letter.variables())
                .containsOnlyKeys(NotificationType.REQUEST_STATUS_CHANGED.requiredVariables())
                .containsEntry("recipientName", "Анна")
                .containsEntry("requestId", request.getId().toString())
                .containsEntry("requestSubject", "Выгрузка за квартал")
                .containsEntry("oldStatus", "В работе")
                .containsEntry("newStatus", "Ждём информацию от вас");
    }

    /** Имени у автора нет — письмо обращается по адресу. */
    @Test
    void statusChanged_whenAuthorHasNoName_addressesByEmail() {
        Request request = request(RequestStatus.CONTACTED);
        authorIs(new UserContactDto(AUTHOR_ID, "anna@example.com", null));

        notifier.statusChanged(request, RequestStatus.NEW);

        assertThat(writtenLetter(request).variables())
                .containsEntry("recipientName", "anna@example.com");
    }

    /** Автор заблокирован или удалён — письма нет; смену статуса это не останавливает. */
    @Test
    void statusChanged_whenAuthorCannotWork_writesNothing() {
        when(users.findActiveContact(TENANT_ID, AUTHOR_ID)).thenReturn(Optional.empty());

        notifier.statusChanged(request(RequestStatus.DONE), RequestStatus.IN_PROGRESS);

        verify(outbox, never()).publish(any(), anyString(), anyString(), any(), anyMap());
    }

    private Request request(RequestStatus status) {
        Request request = new Request(TENANT_ID, AUTHOR_ID, "Выгрузка за квартал", "Нужен отчёт",
                status);
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    private void authorIs(UserContactDto author) {
        when(users.findActiveContact(TENANT_ID, AUTHOR_ID)).thenReturn(Optional.of(author));
    }

    private EmailNotification writtenLetter(Request request) {
        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(outbox).publish(eq(TENANT_ID), eq(EmailNotification.EVENT_TYPE), eq("REQUEST"),
                eq(request.getId()), payload.capture());
        return EmailNotification.fromPayload(payload.getValue());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}

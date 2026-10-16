package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.OverdueEscalation.Deadline;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Эскалация без базы: кому уходят письма, что в них и какие заявки отмечаются. Выборку,
 * блокировки и запись в outbox на реальной базе проверяет {@code OverdueEscalationIntegrationTest}.
 */
class OverdueEscalationTest {

    private static final int BATCH = 10;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant FIRST_RESPONSE_DUE = Instant.parse("2026-10-15T09:30:00Z");
    private static final Instant RESOLUTION_DUE = Instant.parse("2026-10-16T15:00:00Z");

    private static final UserContactDto ASSIGNEE =
            new UserContactDto(UUID.randomUUID(), "olga@example.com", "Ольга");
    private static final UserContactDto ADMIN =
            new UserContactDto(UUID.randomUUID(), "ivan@example.com", "Иван");
    private static final UserContactDto ADMIN_WITHOUT_NAME =
            new UserContactDto(UUID.randomUUID(), "admin@example.com", null);

    private final RequestRepository repository = mock(RequestRepository.class);
    private final UserService users = mock(UserService.class);
    private final TenantTimezoneProvider timezones = mock(TenantTimezoneProvider.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final OverdueEscalation escalation =
            new OverdueEscalation(repository, users, timezones, outbox);

    @BeforeEach
    void setUp() {
        when(timezones.findTimezoneOf(any())).thenReturn(Optional.of(ZoneId.of("Europe/Moscow")));
        when(repository.markFirstResponseEscalated(any()))
                .thenAnswer(call -> call.<Collection<UUID>>getArgument(0).size());
        when(repository.markResolutionEscalated(any()))
                .thenAnswer(call -> call.<Collection<UUID>>getArgument(0).size());
    }

    /** DoD: исполнитель и администраторы получают по письму; имени нет — обращение по адресу. */
    @Test
    void escalateBatch_notifiesAssigneeAndEachAdmin() {
        Request request = request(ASSIGNEE.id());
        lockedForFirstResponse(request);
        assigneeIs(ASSIGNEE);
        adminsAre(ADMIN, ADMIN_WITHOUT_NAME);

        assertThat(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).isEqualTo(1);

        Map<String, EmailNotification> sent = sentByRecipient(3);
        assertThat(sent).containsOnlyKeys(ASSIGNEE.email(), ADMIN.email(),
                ADMIN_WITHOUT_NAME.email());
        assertThat(sent.get(ASSIGNEE.email()).variables()).containsEntry("recipientName", "Ольга");
        assertThat(sent.get(ADMIN_WITHOUT_NAME.email()).variables())
                .containsEntry("recipientName", "admin@example.com");
        verify(repository).markFirstResponseEscalated(List.of(request.getId()));
    }

    /**
     * Письмо — о нарушенном сроке, с данными для шаблона: всеми обязательными и только ими.
     * Срок — по часам арендатора и с названием пояса; событие ссылается на заявку.
     */
    @Test
    void escalateBatch_writesSlaBreachedEmailAboutRequest() {
        Request request = request(null);
        lockedForFirstResponse(request);
        adminsAre(ADMIN);

        escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH);

        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(outbox).publish(eq(TENANT_ID), eq(EmailNotification.EVENT_TYPE), eq("REQUEST"),
                eq(request.getId()), payload.capture());
        EmailNotification email = EmailNotification.fromPayload(payload.getValue());
        assertThat(email.type()).isEqualTo(NotificationType.SLA_BREACHED);
        assertThat(email.variables())
                .containsOnlyKeys(NotificationType.SLA_BREACHED.requiredVariables())
                .containsEntry("recipientName", "Иван")
                .containsEntry("requestId", request.getId().toString())
                .containsEntry("requestSubject", "Выгрузка за квартал")
                .containsEntry("deadlineName", "первого ответа")
                .containsEntry("dueAt", "15.10.2026 12:30 (Europe/Moscow)");
    }

    /** Срок решения: своё название и свой срок, отметка — в своём признаке. */
    @Test
    void escalateBatch_resolution_reportsResolutionDeadline() {
        Request request = request(null);
        when(repository.lockResolutionToEscalate(BATCH)).thenReturn(List.of(request));
        adminsAre(ADMIN);

        assertThat(escalation.escalateBatch(Deadline.RESOLUTION, BATCH)).isEqualTo(1);

        assertThat(sentByRecipient(1).get(ADMIN.email()).variables())
                .containsEntry("deadlineName", "решения")
                .containsEntry("dueAt", "16.10.2026 18:00 (Europe/Moscow)");
        verify(repository).markResolutionEscalated(List.of(request.getId()));
        verify(repository, never()).markFirstResponseEscalated(any());
    }

    /** Исполнитель — сам администратор: одно письмо, а не два. */
    @Test
    void escalateBatch_whenAssigneeIsAdmin_notifiesHimOnce() {
        lockedForFirstResponse(request(ADMIN.id()));
        assigneeIs(ADMIN);
        adminsAre(ADMIN);

        escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH);

        assertThat(sentByRecipient(1)).containsOnlyKeys(ADMIN.email());
    }

    /** DoD: заявка без исполнителя эскалируется администраторам. */
    @Test
    void escalateBatch_whenNoAssignee_notifiesAdminsOnly() {
        lockedForFirstResponse(request(null));
        adminsAre(ADMIN);

        escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH);

        assertThat(sentByRecipient(1)).containsOnlyKeys(ADMIN.email());
        verify(users, never()).findActiveContact(any(), any());
    }

    /** Исполнитель заблокирован или удалён — пишем только администраторам. */
    @Test
    void escalateBatch_whenAssigneeCannotWork_notifiesAdminsOnly() {
        lockedForFirstResponse(request(ASSIGNEE.id()));
        when(users.findActiveContact(TENANT_ID, ASSIGNEE.id())).thenReturn(Optional.empty());
        adminsAre(ADMIN);

        escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH);

        assertThat(sentByRecipient(1)).containsOnlyKeys(ADMIN.email());
    }

    /**
     * Писать некому — заявка всё равно отмечается: иначе каждый проход выбирал бы её снова и
     * останавливался на ней.
     */
    @Test
    void escalateBatch_whenNobodyToNotify_marksRequestAnyway() {
        Request request = request(null);
        lockedForFirstResponse(request);
        adminsAre();

        assertThat(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).isEqualTo(1);

        verify(outbox, never()).publish(any(), anyString(), anyString(), any(), anyMap());
        verify(repository).markFirstResponseEscalated(List.of(request.getId()));
    }

    /** Администраторы и пояс арендатора запрашиваются один раз на порцию, а не на заявку. */
    @Test
    void escalateBatch_looksUpAdminsAndTimezoneOncePerTenant() {
        lockedForFirstResponse(request(null), request(null), request(null));
        adminsAre(ADMIN);

        assertThat(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).isEqualTo(3);

        verify(users, times(1)).findActiveWithPermission(TENANT_ID, "SLA_POLICY_MANAGE");
        verify(timezones, times(1)).findTimezoneOf(TENANT_ID);
        verify(outbox, times(3)).publish(any(), anyString(), anyString(), any(), anyMap());
    }

    /** Настроек арендатора нет — срок по UTC, а не по поясу сервера. */
    @Test
    void escalateBatch_whenTenantHasNoTimezone_formatsDueAtInUtc() {
        when(timezones.findTimezoneOf(TENANT_ID)).thenReturn(Optional.empty());
        lockedForFirstResponse(request(null));
        adminsAre(ADMIN);

        escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH);

        assertThat(sentByRecipient(1).get(ADMIN.email()).variables())
                .containsEntry("dueAt", "15.10.2026 09:30 (UTC)");
    }

    /**
     * Запись письма не удалась — заявка не отмечается: ошибка откатит транзакцию порции, и
     * следующий проход попробует снова.
     */
    @Test
    void escalateBatch_whenOutboxFails_marksNothing() {
        lockedForFirstResponse(request(null));
        adminsAre(ADMIN);
        when(outbox.publish(any(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new IllegalStateException("База недоступна"));

        assertThatThrownBy(() -> escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).markFirstResponseEscalated(any());
    }

    @Test
    void escalateBatch_whenNothingToEscalate_marksNothing() {
        when(repository.lockFirstResponseToEscalate(BATCH)).thenReturn(List.of());

        assertThat(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).isZero();

        verify(repository, never()).markFirstResponseEscalated(any());
        verify(outbox, never()).publish(any(), anyString(), anyString(), any(), anyMap());
    }

    private Request request(UUID assigneeId) {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", RequestStatus.NEW);
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.changeAssignee(assigneeId, FIRST_RESPONSE_DUE.minusSeconds(3600));
        request.setFirstResponseDueAt(FIRST_RESPONSE_DUE);
        request.setResolutionDueAt(RESOLUTION_DUE);
        return request;
    }

    private void lockedForFirstResponse(Request... batch) {
        when(repository.lockFirstResponseToEscalate(anyInt())).thenReturn(List.of(batch));
    }

    private void assigneeIs(UserContactDto assignee) {
        when(users.findActiveContact(TENANT_ID, assignee.id())).thenReturn(Optional.of(assignee));
    }

    private void adminsAre(UserContactDto... admins) {
        when(users.findActiveWithPermission(TENANT_ID, "SLA_POLICY_MANAGE"))
                .thenReturn(List.of(admins));
    }

    /** Записанные письма по адресу получателя; заодно проверяет, что их ровно столько. */
    private Map<String, EmailNotification> sentByRecipient(int expectedCount) {
        ArgumentCaptor<Map<String, Object>> payloads = payloadCaptor();
        verify(outbox, times(expectedCount)).publish(eq(TENANT_ID),
                eq(EmailNotification.EVENT_TYPE), eq("REQUEST"), any(), payloads.capture());
        return payloads.getAllValues().stream()
                .map(EmailNotification::fromPayload)
                .collect(Collectors.toMap(EmailNotification::recipientEmail, email -> email));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}

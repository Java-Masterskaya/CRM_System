package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Запись события без базы: репозиторий — заглушка. Транзакцию, откат и доставку проверяет
 * интеграционный тест.
 */
class OutboxEventPublisherTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String EVENT_TYPE = "REQUEST_STATUS_CHANGED";
    private static final String LONG_TYPE = "T".repeat(OutboxEvent.TYPE_MAX_LENGTH + 1);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    /** По умолчанию контекст пуст — как у фоновой задачи. */
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final OutboxEventPublisher publisher =
            new OutboxEventPublisher(repository, tenantContext);

    /** Запрос арендатора пишет события только своего арендатора — и оба вида событий. */
    @Test
    void publish_forAnotherTenantThanCurrentRequest_isRejectedWithoutStoring() {
        when(tenantContext.getCurrentTenantId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, "REQUEST",
                UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void publish_forTenantOfCurrentRequest_isStored() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(TENANT_ID, EVENT_TYPE, Map.of());

        assertThat(saved().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void publish_storesNewEventWithTenantTypeAndPayload() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(TENANT_ID, EVENT_TYPE, Map.of("newStatus", "DONE"));

        assertThat(saved()).satisfies(event -> {
            assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(event.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(event.getPayload()).containsExactly(Map.entry("newStatus", "DONE"));
            assertThat(event.getAggregateType()).isNull();
            assertThat(event.getAggregateId()).isNull();
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getAttempts()).isZero();
        });
    }

    @Test
    void publish_aboutObject_storesReferenceToIt() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UUID requestId = UUID.randomUUID();

        publisher.publish(TENANT_ID, EVENT_TYPE, "REQUEST", requestId, Map.of());

        assertThat(saved()).satisfies(event -> {
            assertThat(event.getAggregateType()).isEqualTo("REQUEST");
            assertThat(event.getAggregateId()).isEqualTo(requestId);
        });
    }

    /** Нагрузка копируется: то, что вызывающий меняет потом, в событие не попадает. */
    @Test
    void publish_whenCallerChangesPayloadAfterwards_keepsWhatWasPublished() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> payload = new HashMap<>(Map.of("newStatus", "DONE"));

        publisher.publish(TENANT_ID, EVENT_TYPE, payload);
        payload.put("newStatus", "CANCELLED");

        assertThat(saved().getPayload()).containsExactly(Map.entry("newStatus", "DONE"));
    }

    @Test
    void publish_withIncompleteEvent_isRejectedWithoutStoring() {
        Map<String, Object> payload = Map.of("n", 1);

        assertThatThrownBy(() -> publisher.publish(null, EVENT_TYPE, payload))
                .as("без арендатора").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, " ", payload))
                .as("пустой тип").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, LONG_TYPE, payload))
                .as("тип длиннее колонки").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, null))
                .as("без нагрузки").isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void publish_aboutObject_withIncompleteReference_isRejectedWithoutStoring() {
        Map<String, Object> payload = Map.of("n", 1);

        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, "REQUEST", null,
                payload)).as("без идентификатора").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, null,
                UUID.randomUUID(), payload)).as("без вида объекта")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(TENANT_ID, EVENT_TYPE, LONG_TYPE,
                UUID.randomUUID(), payload)).as("вид длиннее колонки")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(null, EVENT_TYPE, "REQUEST",
                UUID.randomUUID(), payload)).as("без арендатора")
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}

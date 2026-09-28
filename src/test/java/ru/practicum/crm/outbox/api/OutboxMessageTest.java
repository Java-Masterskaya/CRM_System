package ru.practicum.crm.outbox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    @Test
    void create_whenSourcePayloadChangesLater_keepsOriginalValues() {
        Map<String, Object> source = new HashMap<>(Map.of("n", 1));

        OutboxMessage message = message(source);
        source.put("n", 2);

        assertThat(message.payload()).containsExactly(Map.entry("n", 1));
    }

    @Test
    void payload_whenSenderTriesToChangeIt_isRejected() {
        OutboxMessage message = message(Map.of("n", 1));

        assertThatThrownBy(() -> message.payload().put("n", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void create_whenPayloadHasNullValue_keepsIt() {
        Map<String, Object> source = new HashMap<>();
        source.put("comment", null);

        assertThat(message(source).payload()).containsEntry("comment", null);
    }

    private static OutboxMessage message(Map<String, Object> payload) {
        return new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), "TEST", payload, 1);
    }
}

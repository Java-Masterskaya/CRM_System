package ru.practicum.crm.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailNotificationTest {

    private static final EmailNotification NOTIFICATION = new EmailNotification(
            "client@example.com", NotificationType.REQUEST_STATUS_CHANGED,
            Map.of("recipientName", "Анна", "requestId", "42", "requestSubject", "Выгрузка",
                    "oldStatus", "Новая", "newStatus", "В работе"));

    @Test
    void fromPayload_afterJsonRoundTrip_restoresSameNotification() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(NOTIFICATION.toPayload());

        Map<String, Object> stored = mapper.readValue(json, new TypeReference<>() {});

        assertThat(EmailNotification.fromPayload(stored)).isEqualTo(NOTIFICATION);
    }

    @Test
    void fromPayload_whenPayloadDescribesSomethingElse_isRejected() {
        assertThatThrownBy(() -> EmailNotification.fromPayload(Map.of("n", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPayload_whenTypeIsUnknown_isRejected() {
        Map<String, Object> payload = NOTIFICATION.toPayload();
        payload.put("notificationType", "NO_SUCH_TYPE");

        assertThatThrownBy(() -> EmailNotification.fromPayload(payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_whenRecipientIsBlank_isRejected() {
        assertThatThrownBy(() -> new EmailNotification(" ", NotificationType.REQUEST_CREATED,
                Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_whenValueForTemplateIsMissing_isAcceptedAndLeftForLetterAssembly() {
        assertThatCode(() -> new EmailNotification("client@example.com",
                NotificationType.REQUEST_CREATED, Map.of())).doesNotThrowAnyException();
    }
}

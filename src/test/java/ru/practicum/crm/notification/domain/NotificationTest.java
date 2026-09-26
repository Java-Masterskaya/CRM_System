package ru.practicum.crm.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.notification.api.NotificationType;

class NotificationTest {

    private static final Instant SENT_AT = Instant.parse("2026-09-26T10:00:00Z");

    private final Notification notification = new Notification(UUID.randomUUID(),
            UUID.randomUUID(), NotificationType.REQUEST_CREATED, "client@example.com");

    @Test
    void markSent_afterFailedAttempt_showsLatestResultWithoutOldReason() {
        notification.markFailed(1, "MAIL_SERVER_UNAVAILABLE");

        notification.markSent(2, SENT_AT);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).isEqualTo(2);
        assertThat(notification.getSentAt()).isEqualTo(SENT_AT);
        assertThat(notification.getLastErrorCode()).isNull();
    }

    @Test
    void markFailed_whenOlderAttemptFinishesAfterNewerOne_keepsNewerResult() {
        notification.markSent(3, SENT_AT);

        notification.markFailed(2, "MAIL_SERVER_UNAVAILABLE");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).isEqualTo(3);
        assertThat(notification.getLastErrorCode()).isNull();
    }
}

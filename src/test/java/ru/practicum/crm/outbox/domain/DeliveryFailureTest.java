package ru.practicum.crm.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryFailureTest {

    @Test
    void failure_whenCodeMissing_isRejected() {
        assertThatThrownBy(() -> new DeliveryFailure(" ", "Сообщение"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryFailure(null, "Сообщение"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failure_whenCodeLongerThanColumn_isRejected() {
        String tooLong = "C".repeat(DeliveryFailure.CODE_MAX_LENGTH + 1);

        assertThatThrownBy(() -> new DeliveryFailure(tooLong, "Сообщение"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failure_whenMessageLongerThanColumn_isCutToFit() {
        String longMessage = "м".repeat(DeliveryFailure.MESSAGE_MAX_LENGTH + 100);

        DeliveryFailure failure = new DeliveryFailure("SMTP_DOWN", longMessage);

        assertThat(failure.message()).hasSize(DeliveryFailure.MESSAGE_MAX_LENGTH);
    }

    @Test
    void failure_whenMessageAbsent_keepsCodeOnly() {
        DeliveryFailure failure = new DeliveryFailure("SMTP_DOWN", null);

        assertThat(failure.code()).isEqualTo("SMTP_DOWN");
        assertThat(failure.message()).isNull();
    }
}

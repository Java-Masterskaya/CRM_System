package ru.practicum.crm.outbox.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxDeliveryExceptionTest {

    @Test
    void exception_createdByConstructor_isTemporary() {
        OutboxDeliveryException exception = new OutboxDeliveryException(
                "MAIL_SERVER_UNAVAILABLE", "Почтовый сервер недоступен");

        assertThat(exception.isPermanent()).isFalse();
        assertThat(exception.getCode()).isEqualTo("MAIL_SERVER_UNAVAILABLE");
    }

    @Test
    void permanent_keepsCodeAndMessageAndForbidsRetry() {
        OutboxDeliveryException exception =
                OutboxDeliveryException.permanent("MAIL_INVALID_MESSAGE", "Неверный адрес");

        assertThat(exception.isPermanent()).isTrue();
        assertThat(exception.getCode()).isEqualTo("MAIL_INVALID_MESSAGE");
        assertThat(exception.getMessage()).isEqualTo("Неверный адрес");
    }
}

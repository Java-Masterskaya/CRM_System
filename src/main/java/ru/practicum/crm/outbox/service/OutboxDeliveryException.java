package ru.practicum.crm.outbox.service;

/**
 * Известная причина, по которой отправитель не смог доставить событие.
 *
 * <p>Код и сообщение сохраняются в событии для разбора, поэтому сообщение не должно содержать
 * адресов, текста письма и токенов: пишите «почтовый сервер недоступен», а не текст ответа
 * сервера. Любое другое исключение отправителя обработчик тоже считает неудачей, но сохраняет
 * только имя его класса.
 */
public class OutboxDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public OutboxDeliveryException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public OutboxDeliveryException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

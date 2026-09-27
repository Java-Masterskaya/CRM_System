package ru.practicum.crm.outbox.api;

/**
 * Известная причина, по которой отправитель не смог доставить событие.
 *
 * <p>Код и сообщение сохраняются в событии для разбора, поэтому сообщение не должно содержать
 * адресов, текста письма и токенов: пишите «почтовый сервер недоступен», а не текст ответа
 * сервера. Любое другое исключение отправителя обработчик тоже считает неудачей, но сохраняет
 * только имя его класса.
 *
 * <p>Неудача по умолчанию временная: событие вернётся в очередь и будет повторено. Если повтор
 * заведомо бесполезен — неверный адрес, отказ сервера принять именно это письмо, — отправитель
 * бросает {@link #permanent(String, String)}, и событие сразу получает окончательный неуспех.
 */
public class OutboxDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean permanent;

    public OutboxDeliveryException(String code, String safeMessage) {
        this(code, safeMessage, false, null);
    }

    public OutboxDeliveryException(String code, String safeMessage, Throwable cause) {
        this(code, safeMessage, false, cause);
    }

    private OutboxDeliveryException(String code, String safeMessage, boolean permanent,
            Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.permanent = permanent;
    }

    /** Неудача, которую повтор не исправит: событие не будет повторяться. */
    public static OutboxDeliveryException permanent(String code, String safeMessage) {
        return new OutboxDeliveryException(code, safeMessage, true, null);
    }

    public String getCode() {
        return code;
    }

    public boolean isPermanent() {
        return permanent;
    }
}

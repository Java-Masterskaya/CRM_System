package ru.practicum.crm.outbox.domain;

/**
 * Причина неудачной попытки доставки: короткий код для отбора и сообщение для разбора.
 *
 * <p>Сообщение сохраняется в базе и не должно содержать адресов, текста письма и токенов —
 * поэтому его формирует код приложения, а не пересказывает внешний сервис. Длинное сообщение
 * обрезается до размера колонки.
 *
 * <p>Постоянная неудача ({@code permanent}) повтором не исправится — например, неверный адрес
 * получателя: событие сразу получает окончательный неуспех. Остальные неудачи временные, и
 * событие повторяется, пока есть попытки.
 */
public record DeliveryFailure(String code, String message, boolean permanent) {

    public static final int CODE_MAX_LENGTH = 50;
    public static final int MESSAGE_MAX_LENGTH = 500;

    /** Временная неудача: повтор может помочь. */
    public DeliveryFailure(String code, String message) {
        this(code, message, false);
    }

    public DeliveryFailure {
        if (code == null || code.isBlank() || code.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("Код причины обязателен и не длиннее "
                    + CODE_MAX_LENGTH + " символов");
        }
        if (message != null && message.length() > MESSAGE_MAX_LENGTH) {
            message = message.substring(0, MESSAGE_MAX_LENGTH);
        }
    }
}

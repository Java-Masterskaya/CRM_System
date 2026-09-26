package ru.practicum.crm.outbox.domain;

/**
 * Причина неудачной попытки доставки: короткий код для отбора и сообщение для разбора.
 *
 * <p>Сообщение сохраняется в базе и не должно содержать адресов, текста письма и токенов —
 * поэтому его формирует код приложения, а не пересказывает внешний сервис. Длинное сообщение
 * обрезается до размера колонки.
 */
public record DeliveryFailure(String code, String message) {

    public static final int CODE_MAX_LENGTH = 50;
    public static final int MESSAGE_MAX_LENGTH = 500;

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

package ru.practicum.crm.notification.channel;

/**
 * Итог отправки письма: принял ли его почтовый сервер, а если нет — почему.
 *
 * <p>Код причины — для отбора и решения о повторе, сообщение — для разбора. Оба формирует
 * канал, а не почтовая библиотека, поэтому в них нет адресов, текста письма и ответов сервера.
 *
 * <p>{@code permanent} — неудача связана с самим письмом (неверный адрес, отказ сервера принять
 * именно его), и повтор её не исправит. Сбои окружения — сервер недоступен, не приняты учётные
 * данные — временные: после их устранения письмо уйдёт.
 */
public record EmailSendResult(boolean sent, String failureCode, String failureMessage,
        boolean permanent) {

    public static EmailSendResult success() {
        return new EmailSendResult(true, null, null, false);
    }

    /** Временная неудача: повтор может помочь. */
    public static EmailSendResult failure(String code, String message) {
        return new EmailSendResult(false, code, message, false);
    }

    /** Постоянная неудача: повторять это письмо бесполезно. */
    public static EmailSendResult permanentFailure(String code, String message) {
        return new EmailSendResult(false, code, message, true);
    }
}

package ru.practicum.crm.notification.channel;

/**
 * Итог отправки письма: принял ли его почтовый сервер, а если нет — почему.
 *
 * <p>Код причины — для отбора и решения о повторе, сообщение — для разбора. Оба формирует
 * канал, а не почтовая библиотека, поэтому в них нет адресов, текста письма и ответов сервера.
 */
public record EmailSendResult(boolean sent, String failureCode, String failureMessage) {

    public static EmailSendResult success() {
        return new EmailSendResult(true, null, null);
    }

    public static EmailSendResult failure(String code, String message) {
        return new EmailSendResult(false, code, message);
    }
}

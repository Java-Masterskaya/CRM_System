package ru.practicum.crm.notification.template;

/**
 * Письмо собрать нельзя: нет шаблона, не хватает значений или шаблон испорчен.
 *
 * <p>Это ошибка конфигурации, а не пользователя, поэтому отдельного кода ошибки API у неё
 * нет: до клиента она доходить не должна, её увидит обработчик доставки в логах.
 */
public class MailTemplateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MailTemplateException(String message) {
        super(message);
    }

    public MailTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}

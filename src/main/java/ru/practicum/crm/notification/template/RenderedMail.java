package ru.practicum.crm.notification.template;

/**
 * Готовое письмо: тема одной строкой и тело в HTML.
 */
public record RenderedMail(String subject, String htmlBody) {
}

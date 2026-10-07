package ru.practicum.crm.notification.api;

import java.util.ArrayList;
import java.util.List;

/**
 * События, о которых пользователь получает письмо (ТЗ §6.5).
 *
 * <p>Каждому событию соответствует каталог шаблонов в ресурсах: {@code mail/<имя>/subject.txt}
 * с темой и {@code mail/<имя>/body.html} с телом письма. Здесь же перечислены значения, без
 * которых письмо собирать нельзя: иначе на их месте в тексте окажется пустота.
 *
 * <p>Перечень лежит в {@code api}: по нему пакеты, где происходят изменения, создают
 * {@link EmailNotification}.
 */
public enum NotificationType {

    REQUEST_CREATED("request-created"),
    REQUEST_STATUS_CHANGED("request-status-changed", "oldStatus", "newStatus"),
    REQUEST_ASSIGNED("request-assigned", "assigneeName"),
    PUBLIC_COMMENT_ADDED("public-comment-added", "commentAuthor", "commentText"),
    SLA_BREACHED("sla-breached", "dueAt");

    private final String templateName;
    private final List<String> specificVariables;

    NotificationType(String templateName, String... specificVariables) {
        this.templateName = templateName;
        this.specificVariables = List.of(specificVariables);
    }

    public String getTemplateName() {
        return templateName;
    }

    /**
     * Значения, обязательные для письма: общие для всех событий плюс свои для этого события.
     */
    public List<String> requiredVariables() {
        List<String> required = new ArrayList<>(List.of("recipientName", "requestId",
                "requestSubject"));
        required.addAll(specificVariables);
        return List.copyOf(required);
    }
}

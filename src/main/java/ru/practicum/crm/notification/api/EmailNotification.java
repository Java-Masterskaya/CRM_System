package ru.practicum.crm.notification.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Письмо-уведомление в виде исходящего события: кому, о чём и данные для шаблона.
 *
 * <p>Пакет, где произошло изменение, записывает событие с типом {@link #EVENT_TYPE} и полезной
 * нагрузкой {@link #toPayload()} в той же транзакции, что и само изменение. Получатель и данные
 * письма фиксируются сразу, поэтому доставка не зависит от того, что станет с заявкой потом.
 *
 * <p>Значения для шаблона — строки: номер, статус или дату в нужном виде готовит тот, кто
 * создаёт уведомление. Какие значения обязательны, задаёт
 * {@link NotificationType#requiredVariables()}; проверяются они при сборке письма, а не здесь:
 * нехватка данных для письма не должна откатывать саму операцию.
 */
public record EmailNotification(String recipientEmail, NotificationType type,
        Map<String, String> variables) {

    public static final String EVENT_TYPE = "EMAIL_NOTIFICATION";

    private static final String RECIPIENT_EMAIL = "recipientEmail";
    private static final String TYPE = "notificationType";
    private static final String VARIABLES = "variables";

    public EmailNotification {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new IllegalArgumentException("Не указан адрес получателя");
        }
        if (type == null) {
            throw new IllegalArgumentException("Не указан тип уведомления");
        }
        if (variables == null) {
            throw new IllegalArgumentException("Не переданы данные для письма");
        }
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /**
     * Полезная нагрузка события. В ней только строки и вложенная таблица строк, поэтому она
     * без потерь проходит через JSON и обратно.
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(RECIPIENT_EMAIL, recipientEmail);
        payload.put(TYPE, type.name());
        payload.put(VARIABLES, new LinkedHashMap<>(variables));
        return payload;
    }

    /**
     * Восстанавливает уведомление из полезной нагрузки события.
     *
     * @throws IllegalArgumentException если нагрузка записана не в этом формате
     */
    public static EmailNotification fromPayload(Map<String, Object> payload) {
        if (!(payload.get(RECIPIENT_EMAIL) instanceof String recipient)
                || !(payload.get(TYPE) instanceof String typeName)
                || !(payload.get(VARIABLES) instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Полезная нагрузка не описывает письмо-уведомление");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        values.forEach((name, value) ->
                variables.put(String.valueOf(name), value == null ? null : String.valueOf(value)));
        return new EmailNotification(recipient, NotificationType.valueOf(typeName), variables);
    }
}

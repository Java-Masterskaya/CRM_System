package ru.practicum.crm.notification.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки уведомлений. Проверяются при старте: без адреса отправителя приложение не
 * запустится.
 *
 * @param mailFrom адрес отправителя писем
 */
@ConfigurationProperties(prefix = "app.notification")
@Validated
public record NotificationProperties(@NotBlank @Email String mailFrom) {
}

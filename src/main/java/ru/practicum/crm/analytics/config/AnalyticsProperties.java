package ru.practicum.crm.analytics.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;


/**
 * Настройка аналитики (префикс {@code app.analitics}).
 *
 * @param maxPeriodDays максимальная длина переода отчета включительно.
 */
@ConfigurationProperties(prefix = "app.analitics")
@Validated
public record AnalyticsProperties(
        @Min(1) @Max(366)
        int maxPeriodDays
) {
}

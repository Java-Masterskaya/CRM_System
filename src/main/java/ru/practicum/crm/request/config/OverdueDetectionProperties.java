package ru.practicum.crm.request.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки фоновой проверки просроченных заявок (префикс {@code app.overdue-detection}).
 *
 * @param batchSize сколько заявок помечается за одну транзакцию
 * @param pollInterval пауза между окончанием одного прохода и началом следующего
 */
@ConfigurationProperties(prefix = "app.overdue-detection")
@Validated
public record OverdueDetectionProperties(
        @Min(1) @Max(1000)
        int batchSize,

        @NotNull Duration pollInterval
) {

    @AssertTrue(message = "app.overdue-detection: poll-interval должен быть больше нуля")
    public boolean isPollIntervalPositive() {
        return pollInterval == null || pollInterval.isPositive();
    }
}

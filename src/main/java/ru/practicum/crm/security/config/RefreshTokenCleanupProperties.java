package ru.practicum.crm.security.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки плановой очистки refresh-токенов (префикс {@code crm.security.refresh-token-cleanup}).
 *
 * @param batchSize сколько токенов удаляется за одну транзакцию
 * @param pollInterval пауза между окончанием одного прохода и началом следующего
 */
@ConfigurationProperties(prefix = "crm.security.refresh-token-cleanup")
@Validated
public record RefreshTokenCleanupProperties(
        @Min(1) @Max(1000)
        int batchSize,

        @NotNull Duration pollInterval
) {

    @AssertTrue(message =
            "crm.security.refresh-token-cleanup: poll-interval должен быть больше нуля")
    public boolean isPollIntervalPositive() {
        return pollInterval == null || pollInterval.isPositive();
    }
}

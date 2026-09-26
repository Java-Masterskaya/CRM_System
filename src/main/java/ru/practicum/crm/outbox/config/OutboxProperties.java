package ru.practicum.crm.outbox.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки фонового обработчика исходящих событий (префикс {@code app.outbox}).
 *
 * @param batchSize сколько событий берётся за один проход
 * @param pollInterval пауза между окончанием одного прохода и началом следующего
 * @param lease на сколько событие закрепляется за взявшим его обработчиком; должно быть больше
 *     самой долгой отправки, иначе событие может быть взято повторно, пока первая отправка ещё
 *     идёт
 * @param retryDelay задержка после первой неудачной попытки; каждая следующая вдвое больше
 * @param maxRetryDelay верхняя граница задержки между попытками
 * @param maxAttempts сколько всего попыток доставки, включая первую; после последней неудачной
 *     событие переходит в окончательный неуспех
 */
@ConfigurationProperties(prefix = "app.outbox")
@Validated
public record OutboxProperties(
        @Min(1) @Max(1000)
        int batchSize,

        @NotNull Duration pollInterval,

        @NotNull Duration lease,

        @NotNull Duration retryDelay,

        @NotNull Duration maxRetryDelay,

        @Min(1) @Max(100)
        int maxAttempts
) {

    @AssertTrue(message = "app.outbox: poll-interval, lease, retry-delay и max-retry-delay "
            + "должны быть больше нуля")
    public boolean isDurationsPositive() {
        return isPositive(pollInterval) && isPositive(lease) && isPositive(retryDelay)
                && isPositive(maxRetryDelay);
    }

    @AssertTrue(message = "app.outbox: max-retry-delay не может быть меньше retry-delay")
    public boolean isRetryDelayWithinMaximum() {
        return retryDelay == null || maxRetryDelay == null
                || maxRetryDelay.compareTo(retryDelay) >= 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration == null || duration.isPositive();
    }
}

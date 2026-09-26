package ru.practicum.crm.outbox.service;

import java.time.Duration;

/**
 * Когда повторять неудачную доставку и когда сдаваться.
 *
 * <p>Задержка растёт экспоненциально: после первой неудачи — {@code initialDelay}, после
 * каждой следующей — вдвое больше, но не больше {@code maxDelay}. Так временный сбой внешнего
 * сервиса переживается несколькими быстрыми повторами, а долгий не забивает очередь частыми
 * попытками.
 */
public final class OutboxRetryPolicy {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final int maxAttempts;

    public OutboxRetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
    }

    /** Сделано столько попыток, сколько разрешено: больше не пробуем. */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * Задержка перед следующей попыткой, если неудачной была попытка номер {@code attemptsMade}
     * (нумерация с единицы): {@code initialDelay × 2^(attemptsMade − 1)}, но не больше
     * {@code maxDelay}. Удвоение прекращается, как только достигнута граница, поэтому большое
     * число попыток не приводит к переполнению.
     */
    public Duration delayAfter(int attemptsMade) {
        Duration delay = initialDelay;
        for (int attempt = 1; attempt < attemptsMade && delay.compareTo(maxDelay) < 0; attempt++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}

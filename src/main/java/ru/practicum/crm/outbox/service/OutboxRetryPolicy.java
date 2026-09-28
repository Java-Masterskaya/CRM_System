package ru.practicum.crm.outbox.service;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Когда повторять неудачную доставку и когда сдаваться.
 *
 * <p>Задержка растёт экспоненциально: после первой неудачи — {@code initialDelay}, после
 * каждой следующей — вдвое больше, но не больше {@code maxDelay}. Так временный сбой внешнего
 * сервиса переживается несколькими быстрыми повторами, а долгий не забивает очередь частыми
 * попытками.
 *
 * <p>К задержке добавляется случайный разброс ({@code jitter}): события, не доставленные в одну и
 * ту же минуту, иначе получили бы одинаковое время повтора и пришли бы к только что
 * восстановившемуся серверу разом. Разброс только в меньшую сторону — задержка берётся из
 * промежутка {@code [d × (1 − jitter); d]}, поэтому {@code maxDelay} остаётся верхней границей.
 */
public final class OutboxRetryPolicy {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final double jitter;
    private final DoubleSupplier random;

    /** Правило без разброса: задержка всегда ровно расчётная. */
    public OutboxRetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts) {
        this(initialDelay, maxDelay, maxAttempts, 0, () -> 0);
    }

    /**
     * Правило с разбросом.
     *
     * @param jitter доля разброса от 0 до 1
     * @param random источник случайных чисел из промежутка {@code [0; 1)}; в тестах подставляется
     *     предсказуемый
     */
    public OutboxRetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts,
            double jitter, DoubleSupplier random) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
        this.jitter = jitter;
        this.random = random;
    }

    /** Сделано столько попыток, сколько разрешено: больше не пробуем. */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * Задержка перед следующей попыткой, если неудачной была попытка номер {@code attemptsMade}
     * (нумерация с единицы): {@code initialDelay × 2^(attemptsMade − 1)}, но не больше
     * {@code maxDelay}, уменьшенная на случайную долю разброса. Удвоение прекращается, как только
     * достигнута граница, поэтому большое число попыток не приводит к переполнению.
     */
    public Duration delayAfter(int attemptsMade) {
        Duration delay = initialDelay;
        for (int attempt = 1; attempt < attemptsMade && delay.compareTo(maxDelay) < 0; attempt++) {
            delay = delay.multipliedBy(2);
        }
        return withJitter(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }

    private Duration withJitter(Duration delay) {
        if (jitter <= 0) {
            return delay;
        }
        double factor = 1 - jitter * random.getAsDouble();
        return Duration.ofMillis(Math.round(delay.toMillis() * factor));
    }
}

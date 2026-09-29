package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

    private final OutboxRetryPolicy policy =
            new OutboxRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(2), 8);

    @Test
    void delayAfter_whenAttemptsKeepFailing_doublesEachTime() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.delayAfter(7)).isEqualTo(Duration.ofMinutes(64));
    }

    @Test
    void delayAfter_whenAttemptsUpToLimit_everyNextDelayIsLonger() {
        for (int attempt = 2; attempt < 8; attempt++) {
            assertThat(policy.delayAfter(attempt))
                    .as("задержка после попытки %d", attempt)
                    .isGreaterThan(policy.delayAfter(attempt - 1));
        }
    }

    @Test
    void delayAfter_whenDoublingReachesMaximum_staysAtMaximum() {
        assertThat(policy.delayAfter(8)).isEqualTo(Duration.ofHours(2));
        assertThat(policy.delayAfter(20)).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void delayAfter_whenAttemptNumberHuge_doesNotOverflow() {
        assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void isExhausted_whenLimitReached_stopsRetrying() {
        assertThat(policy.isExhausted(7)).isFalse();
        assertThat(policy.isExhausted(8)).isTrue();
        assertThat(policy.isExhausted(9)).isTrue();
    }

    @Test
    void delayAfter_withJitter_reducesDelayByRandomShare() {
        // Попытка 3: расчётная задержка 4 минуты; разброс половина → от 2 до 4 минут.
        assertThat(withJitter(0.5, 0.0).delayAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(withJitter(0.5, 0.1).delayAfter(3)).isEqualTo(Duration.ofSeconds(228));
        assertThat(withJitter(0.5, 0.7).delayAfter(3)).isEqualTo(Duration.ofSeconds(156));
        assertThat(withJitter(0.5, 0.999_999).delayAfter(3))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void delayAfter_whenRandomValuesDiffer_givesEventsWithSameAttemptDifferentDelays() {
        Iterator<Double> randomValues = List.of(0.2, 0.8).iterator();
        OutboxRetryPolicy jittered = new OutboxRetryPolicy(Duration.ofMinutes(1),
                Duration.ofHours(2), 8, 0.5, randomValues::next);

        Duration firstEvent = jittered.delayAfter(1);
        Duration secondEvent = jittered.delayAfter(1);

        assertThat(firstEvent).isNotEqualTo(secondEvent);
    }

    @Test
    void delayAfter_withJitterAtMaximum_neverExceedsMaximum() {
        assertThat(withJitter(0.5, 0.0).delayAfter(20)).isEqualTo(Duration.ofHours(2));
        assertThat(withJitter(0.5, 0.5).delayAfter(20)).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void delayAfter_withZeroJitter_isExactlyCalculatedDelay() {
        OutboxRetryPolicy noJitter = withJitter(0, 0.9);

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(noJitter.delayAfter(attempt)).isEqualTo(policy.delayAfter(attempt));
        }
    }

    /** Правило с разбросом {@code jitter}, у которого «случайное» число всегда {@code value}. */
    private static OutboxRetryPolicy withJitter(double jitter, double value) {
        return new OutboxRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(2), 8, jitter,
                () -> value);
    }
}

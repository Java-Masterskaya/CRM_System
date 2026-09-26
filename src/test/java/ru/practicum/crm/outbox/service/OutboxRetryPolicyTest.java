package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
}

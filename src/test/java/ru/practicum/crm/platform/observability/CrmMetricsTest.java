package ru.practicum.crm.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrmMetricsTest {

    private MeterRegistry registry;
    private CrmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CrmMetrics(registry);
    }

    @Test
    void givenClientCreatedCalledThreeTimes_whenCounterRead_thenCountIsThree() {
        metrics.clientCreated();
        metrics.clientCreated();
        metrics.clientCreated();

        double count = registry.get("crm.clients.created")
                .counter()
                .count();

        assertThat(count).isEqualTo(3.0);
    }

    @Test
    void givenSuccessfulAction_whenTimedCalled_thenResultReturnedAndSuccessTimerRecorded() {
        String result = metrics.timed("client.create", () -> "ok");

        assertThat(result).isEqualTo("ok");

        Timer timer = registry.get("crm.operation.duration")
                .tag("operation", "client.create")
                .tag("outcome", "success")
                .timer();

        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    void givenFailingAction_whenTimedCalled_thenExceptionRethrownAndErrorTimerRecorded() {
        assertThatThrownBy(() ->
                metrics.timed("client.create", () -> {
                    throw new IllegalStateException("boom");
                })
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        Timer timer = registry.get("crm.operation.duration")
                .tag("operation", "client.create")
                .tag("outcome", "error")
                .timer();

        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void givenSuccessfulAndFailingActions_whenTimedCalled_thenTimersSeparatedByOutcome() {
        metrics.timed("op", () -> "ok");

        assertThatThrownBy(() -> metrics.timed("op", () -> {
            throw new RuntimeException("fail");
        })).isInstanceOf(RuntimeException.class);

        Timer success = registry.get("crm.operation.duration")
                .tag("operation", "op")
                .tag("outcome", "success")
                .timer();

        Timer error = registry.get("crm.operation.duration")
                .tag("operation", "op")
                .tag("outcome", "error")
                .timer();

        assertThat(success.count()).isEqualTo(1);
        assertThat(error.count()).isEqualTo(1);
    }

    @Test
    void givenActionReturnsNull_whenTimedCalled_thenNullReturnedAndSuccessTimerRecorded() {
        String result = metrics.timed("op.null", () -> null);

        assertThat(result).isNull();

        Timer timer = registry.get("crm.operation.duration")
                .tag("operation", "op.null")
                .tag("outcome", "success")
                .timer();

        assertThat(timer.count()).isEqualTo(1);
    }
}

package ru.practicum.crm.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CrmMetrics {

    private static final String OPERATION_TIMER = "crm.operation.duration";
    private static final String OPERATION_TIMER_DESCRIPTION = "Длительность бизнес-операций";

    private final MeterRegistry registry;

    public void clientCreated() {
        Counter.builder("crm.clients.created")
                .description("Количество созданных клиентов")
                .register(registry)
                .increment();
    }

    public <T> T timed(String operation, Supplier<T> action) {
        var sample = Timer.start(registry);
        var outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder(OPERATION_TIMER)
                    .description(OPERATION_TIMER_DESCRIPTION)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
    }
}

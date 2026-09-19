package ru.practicum.crm.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class CrmMetrics {

    private static final String OPERATION_TIMER = "crm.operation.duration";
    private static final String OPERATION_TIMER_DESCRIPTION = "Длительность бизнес-операций";

    private final MeterRegistry registry;
    private final Counter clientsCreated;

    public CrmMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.clientsCreated = Counter.builder("crm.clients.created")
                .description("Количество созданных клиентов")
                .register(registry);
    }

    public void clientCreated() {
        clientsCreated.increment();
    }

    public <T> T timed(String operation, Supplier<T> action) {
        var sample = Timer.start(registry);
        var outcome = "success";
        try {
            return action.get();
        } catch (Throwable t) {
            outcome = "error";
            throw t;
        } finally {
            sample.stop(Timer.builder(OPERATION_TIMER)
                    .description(OPERATION_TIMER_DESCRIPTION)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }
}

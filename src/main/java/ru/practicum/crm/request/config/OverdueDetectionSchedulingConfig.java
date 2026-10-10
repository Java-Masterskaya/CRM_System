package ru.practicum.crm.request.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import ru.practicum.crm.request.service.OverdueRequestDetector;

/**
 * Запускает фоновую проверку просроченных заявок по расписанию — так же, как обработчик outbox:
 * период берётся из {@link OverdueDetectionProperties} как {@code Duration}. Расписание можно
 * выключить ({@code app.overdue-detection.scheduler-enabled=false}) — так делают тесты, которые
 * вызывают проверку сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.overdue-detection", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class OverdueDetectionSchedulingConfig implements SchedulingConfigurer {

    private final OverdueRequestDetector detector;
    private final OverdueDetectionProperties properties;

    public OverdueDetectionSchedulingConfig(OverdueRequestDetector detector,
            OverdueDetectionProperties properties) {
        this.detector = detector;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(detector::detect, properties.pollInterval());
    }
}

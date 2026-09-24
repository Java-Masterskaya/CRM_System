package ru.practicum.crm.outbox.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import ru.practicum.crm.outbox.service.OutboxProcessor;

/**
 * Запускает обработчик исходящих событий по расписанию.
 *
 * <p>Период берётся из {@link OutboxProperties} как {@code Duration}, а не строкой в
 * {@code @Scheduled}: так все настройки обработчика записываются в одном формате и проверяются
 * при старте. Расписание можно выключить ({@code app.outbox.scheduler-enabled=false}) —
 * так делают тесты, которые вызывают обработчик сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox", name = "scheduler-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxSchedulingConfig implements SchedulingConfigurer {

    private final OutboxProcessor processor;
    private final OutboxProperties properties;

    public OutboxSchedulingConfig(OutboxProcessor processor, OutboxProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(processor::processBatch, properties.pollInterval());
    }
}

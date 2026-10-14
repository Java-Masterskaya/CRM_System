package ru.practicum.crm.security.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import ru.practicum.crm.security.service.RefreshTokenCleaner;

/**
 * Запускает плановую очистку refresh-токенов по расписанию — так же, как обработчик outbox и
 * проверку просроченных заявок: период берётся из {@link RefreshTokenCleanupProperties} как
 * {@code Duration}. Расписание можно выключить
 * ({@code crm.security.refresh-token-cleanup.scheduler-enabled=false}) — так делают тесты,
 * которые вызывают очистку сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "crm.security.refresh-token-cleanup", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupSchedulingConfig implements SchedulingConfigurer {

    private final RefreshTokenCleaner cleaner;
    private final RefreshTokenCleanupProperties properties;

    public RefreshTokenCleanupSchedulingConfig(RefreshTokenCleaner cleaner,
            RefreshTokenCleanupProperties properties) {
        this.cleaner = cleaner;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(cleaner::cleanUp, properties.pollInterval());
    }
}

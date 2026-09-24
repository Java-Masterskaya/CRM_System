package ru.practicum.crm.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import ru.practicum.crm.outbox.service.OutboxProcessor;

class OutboxSchedulingConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, OutboxSchedulingConfig.class)
            .withBean(OutboxProcessor.class, () -> mock(OutboxProcessor.class))
            .withPropertyValues(
                    "app.outbox.batch-size=50",
                    "app.outbox.poll-interval=1h",
                    "app.outbox.lease=5m",
                    "app.outbox.retry-delay=1m");

    @Test
    void scheduler_whenEnabled_runsProcessorWithConfiguredPause() {
        contextRunner.withPropertyValues("app.outbox.scheduler-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboxSchedulingConfig.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                    .singleElement()
                    .extracting(ScheduledTask::getTask)
                    .isInstanceOfSatisfying(FixedDelayTask.class, task ->
                            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1)));
        });
    }

    @Test
    void scheduler_whenSettingAbsent_isOnByDefault() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(OutboxSchedulingConfig.class));
    }

    @Test
    void scheduler_whenDisabled_isNotCreated() {
        contextRunner.withPropertyValues("app.outbox.scheduler-enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(OutboxSchedulingConfig.class));
    }
}

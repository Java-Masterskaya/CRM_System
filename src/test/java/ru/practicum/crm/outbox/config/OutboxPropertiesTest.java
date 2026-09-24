package ru.practicum.crm.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OutboxPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "app.outbox.batch-size=50",
                    "app.outbox.poll-interval=5s",
                    "app.outbox.lease=5m",
                    "app.outbox.retry-delay=1m");

    @Test
    void properties_whenValid_areBoundIncludingShortDurationFormat() {
        contextRunner.run(context -> {
            OutboxProperties properties = context.getBean(OutboxProperties.class);

            assertThat(properties.batchSize()).isEqualTo(50);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.retryDelay()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void batchSize_whenZero_stopsApplicationFromStarting() {
        contextRunner.withPropertyValues("app.outbox.batch-size=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("batchSize");
        });
    }

    @Test
    void lease_whenMissing_stopsApplicationFromStarting() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "app.outbox.batch-size=50",
                        "app.outbox.poll-interval=5s",
                        "app.outbox.retry-delay=1m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("lease");
                });
    }

    @Test
    void duration_whenZero_stopsApplicationFromStarting() {
        contextRunner.withPropertyValues("app.outbox.retry-delay=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("durationsPositive");
        });
    }
}

package ru.practicum.crm.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void startup_whenSenderAddressMissing_failsNamingTheSetting() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("app.notification")
                    .hasMessageContaining("mailFrom");
        });
    }

    @Test
    void startup_whenSenderAddressIsNotEmail_fails() {
        contextRunner.withPropertyValues("app.notification.mail-from=not-an-address")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startup_whenSenderAddressIsValid_bindsIt() {
        contextRunner.withPropertyValues("app.notification.mail-from=noreply@crm.test")
                .run(context -> assertThat(context.getBean(NotificationProperties.class).mailFrom())
                        .isEqualTo("noreply@crm.test"));
    }
}

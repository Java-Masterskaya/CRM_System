package ru.practicum.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConfigurationStartupValidationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DatabaseProperties.class, MailProperties.class})
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "app.mail.host=localhost",
                    "app.mail.port=1025",
                    "app.mail.auth=false"
            );

    @Test
    void shouldFailContextWhenDatabaseUrlIsMissing() {
        contextRunner
                .withPropertyValues(
                        "app.database.username=crm_user",
                        "app.database.password=secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.database.url");
                });
    }

    @Test
    void shouldFailContextWhenDatabasePasswordIsMissing() {
        contextRunner
                .withPropertyValues(
                        "app.database.url=jdbc:postgresql://localhost:5432/crm_db",
                        "app.database.username=crm_user")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.database.password");
                });
    }

    @Test
    void shouldFailContextWhenSmtpAuthEnabledWithoutCredentials() {
        contextRunner
                .withPropertyValues(
                        "app.database.url=jdbc:postgresql://localhost:5432/crm_db",
                        "app.database.username=crm_user",
                        "app.database.password=secret",
                        "app.mail.auth=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("username and password are required");
                });
    }

    @Test
    void shouldStartContextWithCompleteConfiguration() {
        contextRunner
                .withPropertyValues(
                        "app.database.url=jdbc:postgresql://localhost:5432/crm_db",
                        "app.database.username=crm_user",
                        "app.database.password=secret")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

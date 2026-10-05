package ru.practicum.crm.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SystemSecretPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ValidationAutoConfiguration.class))
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void context_whenSystemSecretIsMissing_doesNotStart() {
        contextRunner.run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void context_whenSystemSecretIsBlank_doesNotStart() {
        contextRunner
                .withPropertyValues("crm.system.secret=")
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void context_whenSystemSecretIsProvided_startsSuccessfully() {
        contextRunner
                .withPropertyValues(
                        "crm.system.secret=test-system-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SystemSecretProperties.class);
                    assertThat(context.getBean(SystemSecretProperties.class).secret())
                            .isEqualTo("test-system-secret");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SystemSecretProperties.class)
    static class TestConfiguration {
    }
}

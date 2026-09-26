package ru.practicum.crm.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Настройки из {@code application.yaml} доходят до почтового отправителя, которого создаёт
 * Spring Boot, — тот же объект использует {@link EmailChannel}.
 */
class MailSenderSettingsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=test");

    @Test
    void mailSender_fromApplicationSettings_limitsConnectReadAndWriteTime() {
        contextRunner.run(context -> assertThat(
                context.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "10000")
                .containsEntry("mail.smtp.writetimeout", "10000"));
    }
}

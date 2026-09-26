package ru.practicum.crm.notification.channel;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import ru.practicum.crm.notification.config.NotificationProperties;
import ru.practicum.crm.notification.template.RenderedMail;

/**
 * Канал электронной почты: отдаёт готовое письмо почтовому серверу и сообщает, принял ли тот
 * его.
 *
 * <p>Сбой почты не выходит наружу исключением: вызывающий получает {@link EmailSendResult} с
 * кодом причины и сам решает, повторять ли. Текст исключений почтовой библиотеки не сохраняется
 * и не пишется в лог — в нём бывают адреса получателей и ответы сервера. Ошибки самой
 * программы не перехватываются: это не сбой почты.
 *
 * <p>Сервер, учётные данные и тайм-ауты задаются настройками {@code spring.mail.*}.
 */
@Component
public class EmailChannel {

    public static final String SERVER_UNAVAILABLE = "MAIL_SERVER_UNAVAILABLE";
    public static final String AUTH_FAILED = "MAIL_AUTH_FAILED";
    public static final String REJECTED = "MAIL_REJECTED";
    public static final String INVALID_MESSAGE = "MAIL_INVALID_MESSAGE";

    private final JavaMailSender mailSender;
    private final String from;

    public EmailChannel(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.mailFrom();
    }

    /** Отправляет письмо одному получателю. */
    public EmailSendResult send(String recipient, RenderedMail mail) {
        MimeMessage message;
        try {
            message = compose(recipient, mail);
        } catch (MessagingException ex) {
            return EmailSendResult.failure(INVALID_MESSAGE,
                    "Письмо не собрано: неверный адрес или заголовок");
        }
        try {
            mailSender.send(message);
            return EmailSendResult.success();
        } catch (MailAuthenticationException ex) {
            return EmailSendResult.failure(AUTH_FAILED,
                    "Почтовый сервер не принял учётные данные");
        } catch (MailException ex) {
            if (isNetworkFailure(ex)) {
                return EmailSendResult.failure(SERVER_UNAVAILABLE,
                        "Почтовый сервер недоступен или не ответил вовремя");
            }
            return EmailSendResult.failure(REJECTED, "Почтовый сервер отклонил письмо");
        }
    }

    private MimeMessage compose(String recipient, RenderedMail mail) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject(mail.subject());
        helper.setText(mail.htmlBody(), true);
        return message;
    }

    /**
     * Сетевой сбой — соединение не установилось или сервер не ответил вовремя — виден по
     * {@link IOException} среди причин. Spring кладёт её в причину исключения, если не удалось
     * подключиться, и в исключения по отдельным письмам, если связь оборвалась при передаче.
     */
    private static boolean isNetworkFailure(MailException ex) {
        List<Throwable> failures = new ArrayList<>();
        failures.add(ex);
        if (ex instanceof MailSendException sendFailure) {
            failures.addAll(Arrays.asList(sendFailure.getMessageExceptions()));
        }
        for (Throwable failure : failures) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException) {
                    return true;
                }
            }
        }
        return false;
    }
}

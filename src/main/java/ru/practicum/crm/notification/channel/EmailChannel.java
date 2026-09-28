package ru.practicum.crm.notification.channel;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
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
 * <p>Неудачи делятся на постоянные и временные. Постоянные связаны с самим письмом —
 * неверный адрес, отказ сервера с кодом 5xx — и повтором не исправятся. Временные — сбои
 * окружения: сервер недоступен, не приняты учётные данные, отказ с кодом 4xx.
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
            return EmailSendResult.permanentFailure(INVALID_MESSAGE,
                    "Письмо не собрано: неверный адрес или заголовок");
        }
        try {
            mailSender.send(message);
            return EmailSendResult.success();
        } catch (MailAuthenticationException ex) {
            return EmailSendResult.failure(AUTH_FAILED,
                    "Почтовый сервер не принял учётные данные");
        } catch (MailException ex) {
            List<Throwable> causes = causesOf(ex);
            if (causes.stream().anyMatch(IOException.class::isInstance)) {
                return EmailSendResult.failure(SERVER_UNAVAILABLE,
                        "Почтовый сервер недоступен или не ответил вовремя");
            }
            if (causes.stream().anyMatch(EmailChannel::isPermanentRejection)) {
                return EmailSendResult.permanentFailure(REJECTED,
                        "Почтовый сервер отклонил письмо окончательно");
            }
            return EmailSendResult.failure(REJECTED, "Почтовый сервер отклонил письмо");
        }
    }

    /** Адреса проверяются до отправки: адрес без домена не дойдёт до почтового сервера. */
    private MimeMessage compose(String recipient, RenderedMail mail) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        helper.setValidateAddresses(true);
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject(mail.subject());
        helper.setText(mail.htmlBody(), true);
        return message;
    }

    /**
     * Все исключения, из которых сложилась неудача: само исключение Spring, исключения по
     * отдельным письмам и цепочки их причин. Если не удалось подключиться, Spring кладёт исходную
     * ошибку в причину исключения; если отказ или обрыв случились при передаче — в исключения по
     * отдельным письмам.
     */
    private static List<Throwable> causesOf(MailException ex) {
        List<Throwable> roots = new ArrayList<>();
        roots.add(ex);
        if (ex instanceof MailSendException sendFailure) {
            roots.addAll(Arrays.asList(sendFailure.getMessageExceptions()));
        }
        List<Throwable> causes = new ArrayList<>();
        for (Throwable root : roots) {
            for (Throwable cause = root; cause != null; cause = cause.getCause()) {
                causes.add(cause);
            }
        }
        return causes;
    }

    /**
     * Постоянный отказ — код ответа SMTP 5xx: сервер не примет это письмо и при повторе. Код 4xx
     * означает временный отказ (например, ящик переполнен или сервер перегружен), и повтор уместен.
     * Код ответа есть у трёх исключений Angus Mail, общего предка с ним у них нет.
     */
    private static boolean isPermanentRejection(Throwable cause) {
        int code;
        if (cause instanceof SMTPSendFailedException failure) {
            code = failure.getReturnCode();
        } else if (cause instanceof SMTPAddressFailedException failure) {
            code = failure.getReturnCode();
        } else if (cause instanceof SMTPSenderFailedException failure) {
            code = failure.getReturnCode();
        } else {
            return false;
        }
        return code >= 500 && code < 600;
    }
}

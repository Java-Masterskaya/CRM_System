package ru.practicum.crm.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.practicum.crm.notification.config.NotificationProperties;
import ru.practicum.crm.notification.template.RenderedMail;

/**
 * Канал против почтового сервера внутри теста (GreenMail): настоящий SMTP-обмен, без Docker.
 */
class EmailChannelTest {

    private static final String HOST = "127.0.0.1";
    private static final String FROM = "noreply@crm.test";
    private static final String RECIPIENT = "client@example.com";
    private static final RenderedMail LETTER =
            new RenderedMail("Заявка №42 принята", "<p>Здравствуйте, Анна!</p>");

    private GreenMail mailServer;

    @BeforeEach
    void startMailServer() {
        mailServer = new GreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_SMTP));
        mailServer.start();
    }

    @AfterEach
    void stopMailServer() {
        mailServer.stop();
    }

    @Test
    void send_whenServerAcceptsLetter_deliversHtmlLetterFromConfiguredAddress() throws Exception {
        EmailSendResult result = channel(mailSender(port())).send(RECIPIENT, LETTER);

        assertThat(result).isEqualTo(EmailSendResult.success());
        MimeMessage[] received = mailServer.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage letter = received[0];
        assertThat(letter.getSubject()).isEqualTo("Заявка №42 принята");
        assertThat(letter.getFrom()).extracting(Address::toString).containsExactly(FROM);
        assertThat(letter.getAllRecipients()).extracting(Address::toString)
                .containsExactly(RECIPIENT);
        assertThat(letter.getContentType()).startsWith("text/html")
                .containsIgnoringCase("charset=UTF-8");
        assertThat((String) letter.getContent()).contains("Здравствуйте, Анна!");
    }

    @Test
    void send_whenServerIsDown_reportsServerUnavailableInsteadOfThrowing() {
        int port = port();
        mailServer.stop();

        EmailSendResult result = channel(mailSender(port)).send(RECIPIENT, LETTER);

        assertFailure(result, EmailChannel.SERVER_UNAVAILABLE, false);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void send_whenServerAcceptsConnectionButNeverAnswers_givesUpAfterTimeout() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            EmailSendResult result = channel(mailSender(silentServer.getLocalPort()))
                    .send(RECIPIENT, LETTER);

            assertFailure(result, EmailChannel.SERVER_UNAVAILABLE, false);
        }
    }

    @Test
    void send_whenCredentialsAreWrong_reportsAuthFailure() {
        mailServer.setUser("crm", "right-password");
        JavaMailSenderImpl mailSender = mailSender(port());
        mailSender.setUsername("crm");
        mailSender.setPassword("wrong-password");
        mailSender.getJavaMailProperties().put("mail.smtp.auth", "true");

        EmailSendResult result = channel(mailSender).send(RECIPIENT, LETTER);

        assertFailure(result, EmailChannel.AUTH_FAILED, false);
        assertThat(result.failureMessage()).doesNotContain("wrong-password");
        assertThat(mailServer.getReceivedMessages()).isEmpty();
    }

    @Test
    void send_whenServerRejectsWithCode5xx_reportsPermanentRejection() {
        EmailSendResult result = channel(rejectingWith(550)).send(RECIPIENT, LETTER);

        assertFailure(result, EmailChannel.REJECTED, true);
    }

    @Test
    void send_whenServerRejectsWithCode4xx_reportsRejectionWorthRetrying() {
        EmailSendResult result = channel(rejectingWith(451)).send(RECIPIENT, LETTER);

        assertFailure(result, EmailChannel.REJECTED, false);
    }

    @Test
    void send_whenRejectionCarriesNoSmtpCode_treatsItAsWorthRetrying() {
        JavaMailSenderImpl rejectingSender = new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
                throw new MailSendException("550 Mailbox unavailable: " + RECIPIENT);
            }
        };

        EmailSendResult result = channel(rejectingSender).send(RECIPIENT, LETTER);

        assertFailure(result, EmailChannel.REJECTED, false);
    }

    @Test
    void send_whenRecipientAddressIsMalformed_reportsPermanentInvalidMessage() {
        EmailSendResult result = channel(mailSender(port())).send("client@@example.com", LETTER);

        assertFailure(result, EmailChannel.INVALID_MESSAGE, true);
        assertThat(mailServer.getReceivedMessages()).isEmpty();
    }

    @Test
    void send_whenRecipientAddressHasNoDomain_rejectsItBeforeContactingServer() {
        EmailSendResult result = channel(mailSender(port())).send("client", LETTER);

        assertFailure(result, EmailChannel.INVALID_MESSAGE, true);
        assertThat(mailServer.getReceivedMessages()).isEmpty();
    }

    private static void assertFailure(EmailSendResult result, String expectedCode,
            boolean expectedPermanent) {
        assertThat(result.sent()).isFalse();
        assertThat(result.failureCode()).isEqualTo(expectedCode);
        assertThat(result.permanent()).as("постоянная ли неудача").isEqualTo(expectedPermanent);
        assertThat(result.failureMessage()).isNotBlank().doesNotContain(RECIPIENT);
    }

    /**
     * Отправитель, которому почтовый сервер отказывает с кодом {@code returnCode}: так этот отказ
     * доходит от Angus Mail через Spring — исключением по конкретному письму.
     */
    private static JavaMailSenderImpl rejectingWith(int returnCode) {
        return new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
                Map<Object, Exception> failedMessages = new LinkedHashMap<>();
                failedMessages.put(mimeMessages[0], new SMTPSendFailedException("RCPT TO",
                        returnCode, returnCode + " Recipient <" + RECIPIENT + "> rejected",
                        null, null, null, null));
                throw new MailSendException(failedMessages);
            }
        };
    }

    private int port() {
        return mailServer.getSmtp().getPort();
    }

    private static EmailChannel channel(JavaMailSenderImpl mailSender) {
        return new EmailChannel(mailSender, new NotificationProperties(FROM));
    }

    /**
     * Короткие тайм-ауты: тесту незачем ждать неотвечающий сервер по 10 секунд.
     *
     * <p>Имя машины для приветствия серверу ({@code mail.smtp.localhost}) и домен для заголовка
     * Message-ID ({@code mail.from}) заданы явно. Иначе библиотека ищет полное имя машины через
     * обратный DNS, и там, где он не настроен, тест ждёт ответа секундами.
     */
    private static JavaMailSenderImpl mailSender(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(HOST);
        mailSender.setPort(port);
        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.smtp.localhost", "localhost");
        properties.put("mail.from", FROM);
        properties.put("mail.smtp.connectiontimeout", "1000");
        properties.put("mail.smtp.timeout", "1000");
        properties.put("mail.smtp.writetimeout", "1000");
        return mailSender;
    }
}

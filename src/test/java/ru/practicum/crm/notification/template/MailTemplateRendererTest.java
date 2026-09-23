package ru.practicum.crm.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MailTemplateRendererTest {

    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void render_whenEventSupported_fillsSubjectAndBodyFromTemplates(NotificationType type) {
        Map<String, Object> values = valuesFor(type);

        RenderedMail mail = renderer.render(type, values);

        assertThat(mail.subject()).isNotBlank()
                .doesNotContain("\n", "\r", "${")
                .contains("значение requestSubject");
        for (Object value : values.values()) {
            assertThat(mail.htmlBody()).contains(value.toString());
        }
        assertThat(mail.htmlBody()).doesNotContain("${", "th:");
    }

    @Test
    void render_whenUserTextContainsMarkup_escapesItInBody() {
        Map<String, Object> values = valuesFor(NotificationType.PUBLIC_COMMENT_ADDED);
        values.put("requestSubject", "<script>alert(1)</script>");
        values.put("commentText", "<b>жирный</b> & \"кавычки\"");

        RenderedMail mail = renderer.render(NotificationType.PUBLIC_COMMENT_ADDED, values);

        assertThat(mail.htmlBody())
                .doesNotContain("<script>", "<b>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("&lt;b&gt;жирный&lt;/b&gt; &amp; &quot;кавычки&quot;");
    }

    @Test
    void render_whenUserTextContainsLineBreaks_keepsSubjectOnOneLine() {
        Map<String, Object> values = valuesFor(NotificationType.REQUEST_CREATED);
        values.put("requestSubject", "Отчёт\r\nBcc: someone@example.com");

        RenderedMail mail = renderer.render(NotificationType.REQUEST_CREATED, values);

        assertThat(mail.subject())
                .isEqualTo("Заявка «Отчёт Bcc: someone@example.com» принята");
    }

    @Test
    void render_whenRequiredValueMissing_failsNamingTheValue() {
        Map<String, Object> values = valuesFor(NotificationType.REQUEST_ASSIGNED);
        values.remove("assigneeName");

        assertThatThrownBy(() -> renderer.render(NotificationType.REQUEST_ASSIGNED, values))
                .isInstanceOf(MailTemplateException.class)
                .hasMessageContaining("REQUEST_ASSIGNED")
                .hasMessageContaining("assigneeName");
    }

    @Test
    void render_whenRequiredValueBlank_failsInsteadOfLeavingEmptyPlace() {
        Map<String, Object> values = valuesFor(NotificationType.REQUEST_CREATED);
        values.put("requestSubject", "   ");

        assertThatThrownBy(() -> renderer.render(NotificationType.REQUEST_CREATED, values))
                .isInstanceOf(MailTemplateException.class)
                .hasMessageContaining("requestSubject");
    }

    @Test
    void render_whenTemplateMissing_failsWithClearMessage() {
        MailTemplateRenderer withPartialTemplates = new MailTemplateRenderer("mail-test/");
        Map<String, Object> values = valuesFor(NotificationType.REQUEST_ASSIGNED);

        assertThatThrownBy(() -> withPartialTemplates.render(NotificationType.REQUEST_ASSIGNED,
                values))
                .isInstanceOf(MailTemplateException.class)
                .hasMessageContaining("mail-test/request-assigned/subject.txt")
                .hasMessageContaining("REQUEST_ASSIGNED");
    }

    @Test
    void render_whenTemplateTextChanged_changesMailWithoutCodeChanges() {
        MailTemplateRenderer withOtherTexts = new MailTemplateRenderer("mail-test/");
        Map<String, Object> values = valuesFor(NotificationType.REQUEST_CREATED);

        RenderedMail mail = withOtherTexts.render(NotificationType.REQUEST_CREATED, values);

        assertThat(mail.subject()).isEqualTo("Другая формулировка: значение requestSubject");
        assertThat(mail.htmlBody()).contains("Изменённый текст письма");
    }

    @Test
    void bodyTemplates_whenInspected_neverPrintValuesWithoutEscaping() throws IOException {
        for (NotificationType type : NotificationType.values()) {
            String template = readResource(MailTemplateRenderer.DEFAULT_ROOT
                    + type.getTemplateName() + "/body.html");

            assertThat(template)
                    .as("шаблон %s не должен выводить значения без экранирования", type)
                    .doesNotContain("th:utext", "[(");
        }
    }

    @Test
    void requiredVariables_whenAsked_combineCommonAndEventSpecificOnes() {
        assertThat(NotificationType.REQUEST_STATUS_CHANGED.requiredVariables())
                .containsExactly("recipientName", "requestId", "requestSubject", "oldStatus",
                        "newStatus");
        assertThat(NotificationType.REQUEST_CREATED.requiredVariables())
                .containsExactly("recipientName", "requestId", "requestSubject");
    }

    private static Map<String, Object> valuesFor(NotificationType type) {
        Map<String, Object> values = new HashMap<>();
        for (String name : type.requiredVariables()) {
            values.put(name, "значение " + name);
        }
        return values;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = MailTemplateRenderer.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(stream).as("ресурс %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

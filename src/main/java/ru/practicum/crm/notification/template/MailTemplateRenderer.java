package ru.practicum.crm.notification.template;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateEngineException;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Собирает письмо по событию из шаблонов в ресурсах.
 *
 * <p>Тема — шаблон в текстовом режиме: это строка заголовка письма, а не HTML, поэтому
 * HTML-экранирование к ней неприменимо, зато из неё вырезаются переводы строк и другие
 * управляющие символы. Иначе текст пользователя с переводом строки мог бы дописать в письмо
 * собственные заголовки.
 *
 * <p>Тело — шаблон в режиме HTML. Значения выводятся через {@code th:text}, который экранирует
 * {@code <}, {@code >}, {@code &} и кавычки: текст пользователя попадает в письмо как текст и не
 * становится разметкой.
 *
 * <p>Шаблонизатор создаётся здесь, а не берётся из Spring: подключён только базовый Thymeleaf,
 * без модуля для веб-страниц, поэтому автоконфигурация Spring Boot для MVC-представлений не
 * включается и с письмами не пересекается.
 */
@Component
public class MailTemplateRenderer {

    static final String DEFAULT_ROOT = "mail/";

    private static final String SUBJECT_FILE = "/subject.txt";
    private static final String BODY_FILE = "/body.html";
    private static final Locale LOCALE = Locale.forLanguageTag("ru");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private final String root;
    private final TemplateEngine engine;

    public MailTemplateRenderer() {
        this(DEFAULT_ROOT);
    }

    MailTemplateRenderer(String root) {
        this.root = root;
        this.engine = new TemplateEngine();
        engine.addTemplateResolver(resolver("*.txt", TemplateMode.TEXT, 1));
        engine.addTemplateResolver(resolver("*.html", TemplateMode.HTML, 2));
    }

    /**
     * Собирает письмо о событии.
     *
     * @throws MailTemplateException если для события нет шаблона, не передано обязательное
     *     значение или шаблон не удалось обработать
     */
    public RenderedMail render(NotificationType type, Map<String, ?> variables) {
        requireVariables(type, variables);
        Context context = new Context(LOCALE, new HashMap<>(variables));

        String subject = toHeaderLine(process(type, SUBJECT_FILE, context));
        if (subject.isEmpty()) {
            throw new MailTemplateException("Шаблон темы письма для события " + type
                    + " дал пустую строку.");
        }
        String body = process(type, BODY_FILE, context);
        return new RenderedMail(subject, body);
    }

    private String process(NotificationType type, String file, Context context) {
        String templateName = root + type.getTemplateName() + file;
        if (MailTemplateRenderer.class.getClassLoader().getResource(templateName) == null) {
            throw new MailTemplateException("Нет шаблона письма " + templateName
                    + " для события " + type + ".");
        }
        try {
            return engine.process(templateName, context);
        } catch (TemplateEngineException ex) {
            throw new MailTemplateException("Не удалось собрать письмо по шаблону "
                    + templateName + ".", ex);
        }
    }

    private static void requireVariables(NotificationType type, Map<String, ?> variables) {
        List<String> missing = new ArrayList<>();
        for (String name : type.requiredVariables()) {
            Object value = variables.get(name);
            if (value == null || value instanceof CharSequence text && text.toString().isBlank()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new MailTemplateException("Для письма о событии " + type
                    + " не переданы значения: " + String.join(", ", missing) + ".");
        }
    }

    private static String toHeaderLine(String rendered) {
        String singleLine = CONTROL_CHARACTERS.matcher(rendered).replaceAll(" ");
        return SPACES.matcher(singleLine).replaceAll(" ").strip();
    }

    private static ClassLoaderTemplateResolver resolver(String pattern, TemplateMode mode,
            int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }
}

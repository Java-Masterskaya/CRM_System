package ru.practicum.crm.common.error;

import java.net.URI;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Некорректные входные данные",
            "Исправьте ошибки в указанных полях и повторите запрос."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Некорректный запрос",
            "Запрос не удалось разобрать. Проверьте формат тела и параметров."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Требуется аутентификация",
            "Войдите в систему и повторите запрос."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Неверный email или пароль",
            "Проверьте адрес и пароль и попробуйте снова."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Доступ запрещён",
            "У вас нет прав на эту операцию."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Ресурс не найден",
            "Запрошенный объект не найден."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Метод не поддерживается",
            "Этот HTTP-метод недоступен для указанного адреса."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Формат ответа не поддерживается",
            "Сервис не может вернуть ответ в запрошенном формате. Запросите JSON."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый формат данных",
            "Отправьте тело запроса в формате JSON."),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "Недопустимый переход статуса",
            "Такой переход статуса не разрешён."),
    STALE_VERSION(HttpStatus.CONFLICT, "Данные устарели",
            "Объект изменён другим пользователем. Обновите данные и повторите изменения."),
    ALREADY_EXISTS(HttpStatus.CONFLICT, "Ресурс уже существует",
            "Объект с такими данными уже создан."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Слишком большой запрос",
            "Размер запроса превышает допустимый предел."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Слишком много попыток",
            "Слишком много попыток. Повторите позже."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервиса",
            "Непредвиденная ошибка. Если она повторяется, сообщите идентификатор запроса "
                    + "в поддержку."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Сервис временно недоступен",
            "Сервис временно не может обработать запрос. Повторите позже."),
    CLIENT_ERROR(HttpStatus.BAD_REQUEST, "Ошибка запроса",
            "Запрос не может быть выполнен.");

    private static final String TYPE_BASE = "https://crm.example/problems/";

    private static final Set<ErrorCode> DOMAIN_SPECIFIC = EnumSet.of(VALIDATION_FAILED,
            INVALID_CREDENTIALS, INVALID_TRANSITION, STALE_VERSION, ALREADY_EXISTS);

    private static final Set<ErrorCode> FALLBACK_ONLY = EnumSet.of(CLIENT_ERROR);

    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;
    private final URI type;

    ErrorCode(HttpStatus status, String title, String defaultDetail) {
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
        this.type = URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public static ErrorCode byStatus(int statusValue) {
        for (ErrorCode candidate : values()) {
            if (!DOMAIN_SPECIFIC.contains(candidate) && !FALLBACK_ONLY.contains(candidate)
                    && candidate.status.value() == statusValue) {
                return candidate;
            }
        }
        return statusValue >= 500 ? INTERNAL_ERROR : CLIENT_ERROR;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getDefaultDetail() {
        return defaultDetail;
    }

    public URI getType() {
        return type;
    }
}

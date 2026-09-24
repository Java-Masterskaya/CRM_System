package ru.practicum.crm.common.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String PARAMETERS_DETAIL =
            "Исправьте ошибки в указанных параметрах и повторите запрос.";
    static final String MISSING_PARAMETER_MESSAGE = "обязательный параметр не передан";
    static final String WRONG_TYPE_MESSAGE = "имеет неверный формат";
    static final String ALLOWED_VALUES_MESSAGE = "допустимые значения: ";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(
            OptimisticLockingFailureException ex,
            HttpServletRequest request
    ) {
        LOG.warn("Конфликт версий при {} {}", request.getMethod(), request.getRequestURI());

        ProblemDetail body = ProblemDetailFactory.create(
                ErrorCode.STALE_VERSION,
                null,
                instanceOf(request),
                List.of());

        return ResponseEntity.status(ErrorCode.STALE_VERSION.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex,
            HttpServletRequest request) {
        ProblemDetail body = ProblemDetailFactory.create(ex.getErrorCode(), ex.getDetail(),
                instanceOf(request), ex.getErrors());
        return ResponseEntity.status(ex.getErrorCode().getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex,
            HttpServletRequest request) {
        LOG.error("Необработанная ошибка при {} {}", request.getMethod(),
                request.getRequestURI(), ex);
        ProblemDetail body = ProblemDetailFactory.create(ErrorCode.INTERNAL_ERROR, null,
                instanceOf(request), List.of());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        addFieldErrors(errors, ex.getBindingResult().getFieldErrors(),
                ex.getBindingResult().getGlobalErrors());
        return validationFailed(errors, null, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        boolean onlyParameters = true;
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                addFieldErrors(errors, parameterErrors.getFieldErrors(),
                        parameterErrors.getGlobalErrors());
                onlyParameters = false;
                continue;
            }
            String name = parameterName(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(ValidationError.ofParameter(name, error.getDefaultMessage()));
            }
        }
        return validationFailed(errors, onlyParameters ? PARAMETERS_DETAIL : null, headers,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = List.of(
                ValidationError.ofParameter(ex.getParameterName(), MISSING_PARAMETER_MESSAGE));
        return validationFailed(errors, PARAMETERS_DETAIL, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() : ex.getPropertyName();
        List<ValidationError> errors = List.of(
                ValidationError.ofParameter(name, WRONG_TYPE_MESSAGE));
        return validationFailed(errors, PARAMETERS_DETAIL, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        if (hasCause(ex, PayloadTooLargeException.class)) {
            ProblemDetail body = ProblemDetailFactory.create(ErrorCode.PAYLOAD_TOO_LARGE, null,
                    instanceOf(request), List.of());
            return createResponseEntity(body, headers, ErrorCode.PAYLOAD_TOO_LARGE.getStatus(),
                    request);
        }
        // Значение вне набора допустимых (например, неизвестный приоритет) — не испорченный
        // JSON, а ошибка в конкретном поле, поэтому отвечаем так же, как на невалидное поле.
        InvalidFormatException invalidValue = findCause(ex, InvalidFormatException.class);
        if (invalidValue != null && invalidValue.getTargetType() != null
                && invalidValue.getTargetType().isEnum()) {
            ValidationError error = ValidationError.ofField(fieldPath(invalidValue),
                    ALLOWED_VALUES_MESSAGE + enumNames(invalidValue.getTargetType()));
            return validationFailed(List.of(error), null, headers, request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetailFactory.enrich(problem, ErrorCode.byStatus(statusCode.value()),
                    instanceOf(request));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationFailed(List<ValidationError> errors,
            @Nullable String detail, HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetailFactory.create(ErrorCode.VALIDATION_FAILED, detail,
                instanceOf(request), errors);
        return createResponseEntity(body, headers, ErrorCode.VALIDATION_FAILED.getStatus(),
                request);
    }

    private static void addFieldErrors(List<ValidationError> errors,
            List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        for (FieldError fieldError : fieldErrors) {
            errors.add(ValidationError.ofField(fieldError.getField(),
                    fieldError.getDefaultMessage()));
        }
        for (ObjectError globalError : globalErrors) {
            errors.add(ValidationError.ofField("", globalError.getDefaultMessage()));
        }
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return firstNonEmpty(requestParam.name(), requestParam.value(), parameter);
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return firstNonEmpty(pathVariable.name(), pathVariable.value(), parameter);
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return firstNonEmpty(requestHeader.name(), requestHeader.value(), parameter);
        }
        return parameter.getParameterName();
    }

    private static String firstNonEmpty(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        if (!value.isEmpty()) {
            return value;
        }
        return parameter.getParameterName();
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        return findCause(ex, type) != null;
    }

    @Nullable
    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }

    /**
     * Путь к полю в формате {@code items[0].priority}, из которого строится JSON Pointer.
     * Индекс в самом начале пути (тело — массив) пишется без скобок: {@code 0.priority}, иначе
     * указатель получил бы лишний слэш.
     */
    private static String fieldPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : ex.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                if (path.isEmpty()) {
                    path.append(reference.getIndex());
                } else {
                    path.append('[').append(reference.getIndex()).append(']');
                }
            }
        }
        return path.toString();
    }

    private static String enumNames(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.joining(", "));
    }

    private static URI instanceOf(HttpServletRequest request) {
        return URI.create(request.getRequestURI());
    }

    private static URI instanceOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return instanceOf(servletWebRequest.getRequest());
        }
        return null;
    }
}

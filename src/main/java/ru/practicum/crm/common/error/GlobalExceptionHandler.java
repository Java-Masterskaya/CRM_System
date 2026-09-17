package ru.practicum.crm.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(ValidationError.ofField(fieldError.getField(),
                    fieldError.getDefaultMessage()));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.add(ValidationError.ofField("", globalError.getDefaultMessage()));
        }
        ProblemDetail body = ProblemDetailFactory.create(ErrorCode.VALIDATION_FAILED, null,
                instanceOf(request), errors);
        return createResponseEntity(body, headers, ErrorCode.VALIDATION_FAILED.getStatus(),
                request);
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

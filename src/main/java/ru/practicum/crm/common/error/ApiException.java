package ru.practicum.crm.common.error;

import java.util.List;

public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String detail;
    private final transient List<ValidationError> errors;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    public ApiException(ErrorCode errorCode, String detail, List<ValidationError> errors) {
        super(errorCode.name() + ": " + detail);
        this.errorCode = errorCode;
        this.detail = detail;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }

    public List<ValidationError> getErrors() {
        return errors == null ? List.of() : errors;
    }
}

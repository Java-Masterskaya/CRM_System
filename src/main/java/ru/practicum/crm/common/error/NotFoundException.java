package ru.practicum.crm.common.error;

import java.util.List;

public class NotFoundException extends ApiException {
    public NotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public NotFoundException(ErrorCode errorCode, String detail, List<ValidationError> errors) {
        super(errorCode, detail, errors);
    }
}

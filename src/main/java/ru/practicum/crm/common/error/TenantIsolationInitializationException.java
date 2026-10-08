package ru.practicum.crm.common.error;

public class TenantIsolationInitializationException extends ApiException {
    public TenantIsolationInitializationException(String detail) {
        super(ErrorCode.INTERNAL_ERROR, detail);
    }
}

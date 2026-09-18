package ru.practicum.crm.common.error;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Тело запроса превышает допустимый размер " + limitBytes + " байт");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}

package ru.practicum.crm.analytics.api;

import java.util.List;
import lombok.Getter;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;

/**
 * Разбивка отчета поступления заявок.
 */

public enum InboundGroupBy {
    NONE("Одна серия за весь переод"),
    TYPE("Отдельная серия на каждый тип, у которого за переод были заявки"),
    PRIORITY("Отдельная серия на каждый приоритет, у которого за переод были заявки");

    @Getter
    private final String infoDetails;

    InboundGroupBy(String infoDetails) {
        this.infoDetails = infoDetails;
    }

    public static InboundGroupBy fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }

        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Исправьте параметры отчета",
                    List.of(
                            ValidationError.ofParameter(
                                    "groupBy",
                                    "Допустимые варианты: type, priority, none"
                            )
                    )
            );
        }
    }
}

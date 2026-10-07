package ru.practicum.crm.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationError(String pointer, String parameter, String detail) {

    public static ValidationError ofField(String field, String detail) {
        return new ValidationError(toPointer(field), null, detail);
    }

    public static ValidationError ofParameter(String parameter, String detail) {
        return new ValidationError(null, parameter, detail);
    }

    private static String toPointer(String field) {
        if (field == null || field.isBlank()) {
            return "#";
        }
        String normalized = field.replace("[", ".").replace("]", "").replace('.', '/');
        return "#/" + normalized;
    }
}

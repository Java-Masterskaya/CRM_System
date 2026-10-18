package ru.practicum.crm.common.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JsonDataParamsCopies {

    private JsonDataParamsCopies() {
    }

    public static Map<String, Object> immutableCopy(
            Map<String, Object> source
    ) {
        if (source == null) {
            return null;
        }

        Map<String, Object> copy = new LinkedHashMap<>(source.size());

        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException(
                        "dataParams cannot contain null keys"
                );
            }

            copy.put(key, copyValue(value));
        });

        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Number
                || value instanceof UUID
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAccessor) {
            return value;
        }

        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();

            source.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(
                            "dataParams objects must use string keys"
                    );
                }

                copy.put(stringKey, copyValue(nestedValue));
            });

            return Collections.unmodifiableMap(copy);
        }

        if (value instanceof Collection<?> source) {
            List<Object> copy = new ArrayList<>(source.size());

            for (Object item : source) {
                copy.add(copyValue(item));
            }

            return Collections.unmodifiableList(copy);
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);

            for (int i = 0; i < length; i++) {
                copy.add(copyValue(Array.get(value, i)));
            }

            return Collections.unmodifiableList(copy);
        }

        throw new IllegalArgumentException(
                "dataParams must contain JSON-compatible values"
        );
    }
}

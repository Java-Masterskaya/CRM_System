package ru.practicum.crm.security.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.security.api.PasswordPolicy;
import ru.practicum.crm.security.config.PasswordPolicyProperties;

@Component
@RequiredArgsConstructor
class PasswordPolicyImpl implements PasswordPolicy {

    private final PasswordPolicyProperties properties;

    public List<String> violations(String password) {
        List<String> violations = new ArrayList<>();

        if (password == null) {
            violations.add("пароль обязателен");
            return violations;
        }

        int length = password.codePointCount(0, password.length());

        if (length < properties.minLength()) {
            violations.add(
                    "минимальная длина — " + properties.minLength() + " символов"
            );
        }

        if (properties.requireUppercase()
                && password.codePoints().noneMatch(Character::isUpperCase)) {
            violations.add("должен содержать хотя бы одну заглавную букву");
        }

        if (properties.requireLowercase()
                && password.codePoints().noneMatch(Character::isLowerCase)) {
            violations.add("должен содержать хотя бы одну строчную букву");
        }

        if (properties.requireDigit()
                && password.codePoints().noneMatch(Character::isDigit)) {
            violations.add("должен содержать хотя бы одну цифру");
        }

        if (properties.requireSpecial()
                && password.codePoints().noneMatch(
                        codePoint -> !Character.isLetterOrDigit(codePoint)
                                     && !Character.isWhitespace(codePoint))) {
            violations.add("должен содержать хотя бы один специальный символ");
        }

        return violations;
    }

    public void validate(String password) {
        List<ValidationError> errors = violations(password)
                .stream()
                .map(message -> ValidationError.ofField("password", message))
                .toList();

        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Пароль не соответствует политике безопасности.",
                    errors
            );
        }
    }

}

package ru.practicum.crm.common.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;

/**
 * Текущий пользователь запроса — из предъявленного access-токена (T-026): идентификатор и права.
 *
 * <p>Нужен там, где доступ зависит от самого объекта, — «своя ли это заявка» (T-030). Право на
 * операцию проверяет {@code @PreAuthorize} на методе сервиса, а владение — отдельный шаг в
 * бизнес-логике, и для него нужен этот класс.
 *
 * <p>Лежит в {@code common}, потому что им пользуются разные пакеты: пакет безопасности сам
 * зависит от них, и обратная зависимость дала бы цикл.
 */
@Component
public class CurrentUser {

    /**
     * Идентификатор текущего пользователя — субъект access-токена.
     *
     * @throws ApiException {@code UNAUTHENTICATED}, если запрос пришёл без токена
     */
    public UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw unauthenticated();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            // Например, анонимный пользователь: Spring считает его аутентифицированным, но
            // идентификатора из токена у него нет.
            throw unauthenticated();
        }
    }

    /**
     * Есть ли у текущего пользователя право с этим кодом. Права берутся из токена, без похода в
     * базу; без токена — ни одного права.
     */
    public boolean hasPermission(String permissionCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permissionCode::equals);
    }

    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Требуется аутентификация.");
    }
}

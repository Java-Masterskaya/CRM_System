package ru.practicum.crm.user.api.dto;

import java.util.UUID;

/**
 * Контакт пользователя для писем: кому и как обратиться.
 *
 * @param id идентификатор пользователя
 * @param email адрес
 * @param name имя; пусто, если пользователь его не указал
 */
public record UserContactDto(UUID id, String email, String name) {

    /** Как обратиться в письме: по имени, а если его нет — по адресу. */
    public String displayName() {
        return name == null || name.isBlank() ? email : name;
    }
}

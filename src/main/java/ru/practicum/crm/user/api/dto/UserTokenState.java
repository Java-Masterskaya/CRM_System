package ru.practicum.crm.user.api.dto;

/** Версия доступа и возможность входа на момент блокировки строки пользователя. */
public record UserTokenState(long version, boolean active) {
}

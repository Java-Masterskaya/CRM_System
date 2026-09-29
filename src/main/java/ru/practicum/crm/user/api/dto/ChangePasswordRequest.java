package ru.practicum.crm.user.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;

public record ChangePasswordRequest(
        @NotBlank(message = "Текущий пароль не может быть пустым")
        String currentPassword,
        @NotBlank(message = "Пароль не может быть пустым")
        String newPassword
) {
    @JsonIgnore
    @AssertTrue(message = "Новый и старый пароли совпадают")
    public boolean isTheSamePassword() {
        return Objects.equals(currentPassword(), newPassword());
    }
}

package ru.practicum.crm.user.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record AdminUserCreateRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,
        @NotBlank
        @Size(max = 255)
        String name,
        @NotBlank
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "Временный пароль по действующей политике сложности")
        String temporaryPassword,
        @NotEmpty
        @UniqueElements(message = "роли не должны повторяться")
        List<@NotBlank @Pattern(regexp = "OPERATOR|ADMIN",
                message = "при создании допустимы только OPERATOR и ADMIN") String> roles
) {
    public AdminUserCreateRequest {
        email = email == null ? null : email.trim();
        name = name == null ? null : name.trim();
        roles = roles == null ? null : Collections.unmodifiableList(new ArrayList<>(roles));
    }

    @Override
    public String toString() {
        return "AdminUserCreateRequest[данные скрыты]";
    }
}

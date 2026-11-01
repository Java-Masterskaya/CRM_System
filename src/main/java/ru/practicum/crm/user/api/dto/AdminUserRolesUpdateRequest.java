package ru.practicum.crm.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record AdminUserRolesUpdateRequest(
        @NotEmpty
        @UniqueElements(message = "роли не должны повторяться")
        List<@NotBlank String> roles
) {
    public AdminUserRolesUpdateRequest {
        roles = roles == null ? null : Collections.unmodifiableList(new ArrayList<>(roles));
    }
}

package ru.practicum.crm.request.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Поля заявки, доступные для редактирования оператором или администратором (T-042).
 */
public class EditRequestRequest {

    @NotNull
    private Long version;

    @NotNull
    private RequestPriority priority;

    @NotNull
    private UUID typeId;

    private Instant desiredDueAt;
    private boolean desiredDueAtProvided;

    @NotBlank
    @Size(max = 10_000)
    private String description;

    private final Set<String> unknownProperties = new LinkedHashSet<>();

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public RequestPriority getPriority() {
        return priority;
    }

    public void setPriority(RequestPriority priority) {
        this.priority = priority;
    }

    public UUID getTypeId() {
        return typeId;
    }

    public void setTypeId(UUID typeId) {
        this.typeId = typeId;
    }

    public Instant getDesiredDueAt() {
        return desiredDueAt;
    }

    public void setDesiredDueAt(Instant desiredDueAt) {
        this.desiredDueAt = desiredDueAt;
        this.desiredDueAtProvided = true;
    }

    public boolean isDesiredDueAtProvided() {
        return desiredDueAtProvided;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @JsonAnySetter
    public void captureUnknownProperty(String name, Object value) {
        unknownProperties.add(name);
    }

    public Set<String> getUnknownProperties() {
        return Collections.unmodifiableSet(unknownProperties);
    }
}

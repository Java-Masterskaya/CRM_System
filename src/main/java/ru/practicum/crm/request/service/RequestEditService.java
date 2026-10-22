package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.EditRequestRequest;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

@Service
public class RequestEditService {

    private final RequestRepository requests;
    private final RequestTypeService requestTypes;
    private final RequestAuditService audit;
    private final CurrentUser currentUser;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора внедряется как зависимость Spring.")
    private final TenantContext tenantContext;

    public RequestEditService(
            RequestRepository requests,
            RequestTypeService requestTypes,
            RequestAuditService audit,
            CurrentUser currentUser,
            TenantContext tenantContext
    ) {
        this.requests = requests;
        this.requestTypes = requestTypes;
        this.audit = audit;
        this.currentUser = currentUser;
        this.tenantContext = tenantContext;
    }

    @PreAuthorize("hasAuthority('REQUEST_EDIT')")
    @Transactional
    public RequestCardResponse edit(UUID requestId, EditRequestRequest command) {
        rejectUnknownProperties(command);

        UUID tenantId = tenantContext.getCurrentTenantId();

        Request request = requests
                .findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.NOT_FOUND,
                        "Заявка не найдена."));

        request.requireVersion(command.getVersion());


        requestTypes.requireActive(tenantId, command.getTypeId());

        final RequestSnapshot before = RequestSnapshot.of(request);

        request.setPriority(command.getPriority());
        request.setTypeId(command.getTypeId());
        request.setDesiredDueAt(command.getDesiredDueAt());
        request.setDescription(command.getDescription());

        Request saved = requests.saveAndFlush(request);


        audit.record(saved, before, currentUser.id());

        return toCard(saved);
    }

    private static void rejectUnknownProperties(EditRequestRequest command) {
        if (command.getUnknownProperties().isEmpty()) {
            return;
        }

        List<ValidationError> errors = new ArrayList<>();
        for (String property : command.getUnknownProperties()) {
            errors.add(ValidationError.ofField(
                    property,
                    "Поле нельзя передавать при редактировании заявки."));
        }

        throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Запрос содержит неизвестные или запрещённые поля.",
                errors);
    }

    private static RequestCardResponse toCard(Request request) {
        return new RequestCardResponse(
                request.getId(),
                request.getSubject(),
                request.getDescription(),
                request.getDataParams(),
                request.getStatus(),
                request.getPriority(),
                request.getTypeId(),
                request.getAuthorId(),
                request.getAssigneeId(),
                request.getAssignedAt(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                request.getDesiredDueAt(),
                request.getFirstResponseDueAt(),
                request.getResolutionDueAt(),
                request.isFirstResponseOverdue(),
                request.isResolutionOverdue(),
                request.isOverdue(),
                request.getVersion());
    }
}

package ru.practicum.crm.assignment.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.assignment.api.dto.AssignExecutorRequest;
import ru.practicum.crm.assignment.service.AssignmentService;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserContext;

@RestController
@RequestMapping("/admin/requests/{requestId}")
public class AssignmentController {

    private final AssignmentService assignmentService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "TenantContext acts as a stateless wrapper over ThreadLocal storage. "
                    + "Storing its reference is safe and required to read the thread-bound "
                    + "tenant ID per request"
    )
    private final TenantContext tenantContext;

    private final UserContext userContext;

    public AssignmentController(AssignmentService assignmentService, TenantContext tenantContext,
                                UserContext userContext) {
        this.assignmentService = assignmentService;
        this.tenantContext = tenantContext;
        this.userContext = userContext;
    }

    @PutMapping("/assignee")
    public RequestAssignmentDto assign(@PathVariable UUID requestId,
                                       @Valid @RequestBody AssignExecutorRequest request) {
        return assignmentService.assign(tenantContext.getCurrentTenantId(), requestId,
                request.assigneeId(), request.version(), userContext.getCurrentUserId());
    }

    @PostMapping("/take")
    public RequestAssignmentDto take(@PathVariable UUID requestId) {
        return assignmentService.take(
                tenantContext.getCurrentTenantId(),
                requestId,
                userContext.getCurrentUserId()
        );
    }
}

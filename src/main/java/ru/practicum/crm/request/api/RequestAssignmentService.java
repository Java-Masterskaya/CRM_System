package ru.practicum.crm.request.api;

import java.util.UUID;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.request.api.dto.RequestAssignmentOutcome;

public interface RequestAssignmentService {

    RequestAssignmentDto changeAssignee(UUID tenantId, UUID requestId, UUID assigneeId,
                                        long expectedVersion, UUID actorId);

    RequestAssignmentOutcome takeUnassigned(UUID tenantId, UUID requestId, UUID actorId);
}

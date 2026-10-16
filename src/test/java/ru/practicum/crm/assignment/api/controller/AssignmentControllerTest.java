package ru.practicum.crm.assignment.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.crm.assignment.service.AssignmentService;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.api.dto.RequestAssignmentDto;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserContext;

@WebMvcTest(AssignmentController.class)
@WithMockUser
class AssignmentControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID ASSIGNEE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssignmentService assignmentService;

    @MockBean
    private TenantContext tenantContext;

    @MockBean
    private UserContext userContext;

    @Test
    void assign_whenRequestValid_delegatesToServiceWithTenantAndActorFromContext()
            throws Exception {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(userContext.getCurrentUserId()).thenReturn(ACTOR_ID);
        RequestAssignmentDto response = new RequestAssignmentDto(REQUEST_ID, UUID.randomUUID(),
                "Выгрузка отчёта", ASSIGNEE_ID, Instant.parse("2026-10-13T10:00:00Z"), 1);
        when(assignmentService.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID))
                .thenReturn(response);

        mockMvc.perform(put("/admin/requests/{requestId}/assignee", REQUEST_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + ASSIGNEE_ID + "\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(ASSIGNEE_ID.toString()))
                .andExpect(jsonPath("$.version").value(1));

        verify(assignmentService).assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID);
    }

    @Test
    void assign_whenAssigneeIdNull_isInterpretedAsUnassign() throws Exception {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(userContext.getCurrentUserId()).thenReturn(ACTOR_ID);
        when(assignmentService.assign(TENANT_ID, REQUEST_ID, null, 2, ACTOR_ID)).thenReturn(
                new RequestAssignmentDto(REQUEST_ID, UUID.randomUUID(), "Выгрузка отчёта", null,
                        null, 3));

        mockMvc.perform(put("/admin/requests/{requestId}/assignee", REQUEST_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":null,\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").doesNotExist());

        verify(assignmentService).assign(TENANT_ID, REQUEST_ID, null, 2, ACTOR_ID);
    }

    @Test
    void assign_whenVersionMissing_isRejectedAsValidationErrorWithoutCallingService()
            throws Exception {
        mockMvc.perform(put("/admin/requests/{requestId}/assignee", REQUEST_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + ASSIGNEE_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(assignmentService, never()).assign(any(), any(), any(), any(Long.class), any());
    }

    @Test
    void assign_whenRequestNotFound_propagatesAsNotFoundProblemDetail() throws Exception {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(userContext.getCurrentUserId()).thenReturn(ACTOR_ID);
        when(assignmentService.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID))
                .thenThrow(new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена"));

        mockMvc.perform(put("/admin/requests/{requestId}/assignee", REQUEST_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + ASSIGNEE_ID + "\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void assign_whenCandidateLacksPermission_propagatesAsForbiddenProblemDetail()
            throws Exception {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(userContext.getCurrentUserId()).thenReturn(ACTOR_ID);
        when(assignmentService.assign(TENANT_ID, REQUEST_ID, ASSIGNEE_ID, 0, ACTOR_ID))
                .thenThrow(new ApiException(ErrorCode.ACCESS_DENIED,
                        "У кандидата нет права вести заявку"));

        mockMvc.perform(put("/admin/requests/{requestId}/assignee", REQUEST_ID)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":\"" + ASSIGNEE_ID + "\",\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}

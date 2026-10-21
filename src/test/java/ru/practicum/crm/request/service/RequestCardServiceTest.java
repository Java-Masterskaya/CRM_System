package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

class RequestCardServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private final RequestRepository repository = mock(RequestRepository.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final RequestCardService service = new RequestCardService(repository, tenantContext);

    @Test
    void get_mapsEveryFieldOfRequest() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        UUID authorId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        Instant assignedAt = Instant.now();
        Request request = new Request(TENANT_ID, authorId, "Тема", "Описание",
                RequestStatus.IN_PROGRESS);
        request.setPriority(RequestPriority.HIGH);
        request.setDataParams(Map.of("key", "value"));
        request.setDesiredDueAt(Instant.now().plusSeconds(3600));
        request.setFirstResponseDueAt(Instant.now().plusSeconds(1800));
        request.setResolutionDueAt(Instant.now().plusSeconds(7200));
        request.setFirstResponseOverdue(true);
        request.setResolutionOverdue(false);
        request.changeAssignee(assigneeId, assignedAt);
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        RequestCardResponse card = service.get(REQUEST_ID);

        assertThat(card.id()).isEqualTo(request.getId());
        assertThat(card.subject()).isEqualTo("Тема");
        assertThat(card.description()).isEqualTo("Описание");
        assertThat(card.dataParams()).isEqualTo(Map.of("key", "value"));
        assertThat(card.status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(card.priority()).isEqualTo(RequestPriority.HIGH);
        assertThat(card.typeId()).isEqualTo(request.getTypeId());
        assertThat(card.authorId()).isEqualTo(authorId);
        assertThat(card.assigneeId()).isEqualTo(assigneeId);
        assertThat(card.assignedAt()).isEqualTo(assignedAt);
        assertThat(card.createdAt()).isEqualTo(request.getCreatedAt());
        assertThat(card.updatedAt()).isEqualTo(request.getUpdatedAt());
        assertThat(card.desiredDueAt()).isEqualTo(request.getDesiredDueAt());
        assertThat(card.firstResponseDueAt()).isEqualTo(request.getFirstResponseDueAt());
        assertThat(card.resolutionDueAt()).isEqualTo(request.getResolutionDueAt());
        assertThat(card.firstResponseOverdue()).isTrue();
        assertThat(card.resolutionOverdue()).isFalse();
        assertThat(card.overdue()).isEqualTo(request.isOverdue());
        assertThat(card.version()).isEqualTo(request.getVersion());
    }

    @Test
    void get_whenMissing_isNotFound() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        NotFoundException thrown = catchThrowableOfType(() -> service.get(REQUEST_ID),
                NotFoundException.class);

        assertThat(thrown).isNotNull();
    }
}

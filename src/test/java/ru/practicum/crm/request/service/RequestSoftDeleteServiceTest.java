package ru.practicum.crm.request.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.model.NotDeletedFilter;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

class RequestSoftDeleteServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final RequestRepository repository = mock(RequestRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final RequestAuditService auditService = mock(RequestAuditService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final RequestSoftDeleteService service =
            new RequestSoftDeleteService(repository, entityManager, auditService, currentUser,
                    tenantContext);

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(currentUser.id()).thenReturn(ACTOR_ID);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.getEnabledFilter(NotDeletedFilter.NAME)).thenReturn(mock(Filter.class));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            Request saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "version", saved.getVersion() + 1);
            return saved;
        });
    }

    @Test
    void delete_markRequestSoftDeletedAndWritesAudit() {
        Request request = new Request(
                TENANT_ID,
                AUTHOR_ID,
                "Какая-то тема",
                "Какое-то описание",
                RequestStatus.IN_PROGRESS
        );
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));
        when(repository.save(request)).thenReturn(request);

        service.delete(REQUEST_ID);

        assertThat(request.isDeleted()).isTrue();
        assertThat(request.getDeletedAt()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        verify(repository).save(request);
        verify(auditService).record(eq(request), any(RequestSnapshot.class), eq(ACTOR_ID));
    }

    @Test
    void delete_whenMissing_isNotFound() {
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(REQUEST_ID))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void restore_clearsDeletedAndReturnsFlushedVersion() {
        Request request = new Request(
                TENANT_ID,
                AUTHOR_ID,
                "Какая-то тема",
                "Какое-то описание",
                RequestStatus.CONTACTED
        );
        ReflectionTestUtils.setField(request, "version", 3L);
        request.softDelete(Instant.parse("2026-10-22T10:00:00Z"));
        when(repository.findByIdAndTenantId(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));
        when(repository.save(request)).thenAnswer(invocation -> invocation.getArgument(0));

        RequestStatusResponse response = service.restore(REQUEST_ID);

        assertThat(request.isDeleted()).isFalse();
        assertThat(response.status()).isEqualTo(RequestStatus.CONTACTED);
        assertThat(response.version()).isEqualTo(4L);
        verify(repository).saveAndFlush(request);
        verify(auditService).record(eq(request), any(RequestSnapshot.class), eq(ACTOR_ID));
        verify(session).disableFilter(NotDeletedFilter.NAME);
        verify(session).enableFilter(NotDeletedFilter.NAME);
    }

    @Test
    void restore_whenNotDeleted_isConflict() {
        Request request = new Request(TENANT_ID, AUTHOR_ID, "Тема", "Описание",
                RequestStatus.NEW);

        when(repository.findByIdAndTenantId(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.restore(REQUEST_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REQUEST_NOT_DELETED));
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any());
    }

    @Test
    void restore_whenMissing_isNotFoundAndRenewablesFilter() {
        when(repository.findByIdAndTenantId(REQUEST_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(REQUEST_ID))
                .isInstanceOf(NotFoundException.class);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(session).disableFilter(filter.capture());
        assertThat(filter.getValue()).isEqualTo(NotDeletedFilter.NAME);
        verify(session).enableFilter(NotDeletedFilter.NAME);
    }
}

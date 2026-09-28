package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.AuditedField;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestAuditEntry;
import ru.practicum.crm.request.domain.RequestPriority;
import ru.practicum.crm.request.domain.RequestSnapshot;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestAuditLogRepository;

/** Сервис без базы: репозиторий — заглушка, транзакции проверяет интеграционный тест. */
class RequestAuditServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private final RequestAuditLogRepository repository = mock(RequestAuditLogRepository.class);
    private final RequestAuditService service = new RequestAuditService(repository);
    private final Request request = savedRequest();

    @Test
    @SuppressWarnings("unchecked")
    void record_whenFieldsChanged_savesOneEntryPerFieldWithTenantRequestAndAuthor() {
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.setPriority(RequestPriority.HIGH);
        request.setDescription("Уточнённое описание");

        service.record(request, before, ADMIN_ID);

        ArgumentCaptor<Iterable<RequestAuditEntry>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(RequestAuditEntry::getField)
                .containsExactly(AuditedField.PRIORITY, AuditedField.DESCRIPTION);
        assertThat(saved.getValue()).allSatisfy(entry -> {
            assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(entry.getRequestId()).isEqualTo(REQUEST_ID);
            assertThat(entry.getAuthorId()).isEqualTo(ADMIN_ID);
        });
    }

    @Test
    void record_whenNothingSignificantChanged_returnsNoChanges() {
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.setSubject("Другая тема");

        assertThat(service.record(request, before, ADMIN_ID)).isEmpty();
    }

    @Test
    void record_whenAuthorMissing_isRejectedWithoutWriting() {
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.setPriority(RequestPriority.HIGH);

        assertThatThrownBy(() -> service.record(request, before, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void journal_readsGivenPageOfGivenTenantsRequest() {
        PageRequest largestPage = PageRequest.of(1, PageRequests.MAX_SIZE);

        service.journal(TENANT_ID, REQUEST_ID, largestPage);

        verify(repository).findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(TENANT_ID,
                REQUEST_ID, largestPage);
    }

    @Test
    void journal_whenPageLargerThanLimit_isRejectedWithoutReading() {
        PageRequest tooLarge = PageRequest.of(0, PageRequests.MAX_SIZE + 1);

        assertThatThrownBy(() -> service.journal(TENANT_ID, REQUEST_ID, tooLarge))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void journal_withoutPaging_isRejectedWithoutReading() {
        assertThatThrownBy(() -> service.journal(TENANT_ID, REQUEST_ID, Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static Request savedRequest() {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Выгрузка",
                "Нужен отчёт", RequestStatus.NEW);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        return request;
    }
}

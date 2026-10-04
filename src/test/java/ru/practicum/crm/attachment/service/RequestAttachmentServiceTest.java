package ru.practicum.crm.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import ru.practicum.crm.attachment.domain.FileReference;
import ru.practicum.crm.attachment.domain.RequestAttachment;
import ru.practicum.crm.attachment.repository.RequestAttachmentRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Сервис без базы: репозиторий — заглушка; хранение и ограничения базы проверяет
 * интеграционный тест.
 */
class RequestAttachmentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final FileReference REPORT =
            new FileReference("media-object-42", "отчёт.pdf", 2048, "application/pdf");

    private final RequestAttachmentRepository repository =
            mock(RequestAttachmentRepository.class);
    private final RequestAttachmentService service = new RequestAttachmentService(repository);

    @Test
    void attach_whenReferenceValid_savesLinkWithTenantRequestAuthorAndFileDetails() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RequestAttachment saved = service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, REPORT);

        verify(repository).save(saved);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getObjectId()).isEqualTo("media-object-42");
        assertThat(saved.getFileName()).isEqualTo("отчёт.pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(2048);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void attach_whenObjectAlreadyAttachedToRequest_isRejectedAndNothingSaved() {
        when(repository.existsByTenantIdAndRequestIdAndObjectId(TENANT_ID, REQUEST_ID,
                "media-object-42")).thenReturn(true);

        ApiException thrown = catchThrowableOfType(
                () -> service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, REPORT),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).save(any());
    }

    @Test
    void attach_whenSeveralFieldsInvalid_isRejectedWithAllOfThemAndNothingRead() {
        FileReference broken = new FileReference(" ", null, -1, "");

        ApiException thrown = catchThrowableOfType(
                () -> service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, broken),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("objectId", "не должно быть пустым"),
                ValidationError.ofField("fileName", "не должно быть пустым"),
                ValidationError.ofField("contentType", "не должно быть пустым"),
                ValidationError.ofField("sizeBytes", "не может быть отрицательным"));
        verifyNoInteractions(repository);
    }

    /** Иначе {@code " media-object-42"} привязался бы к заявке вторым объектом рядом с исходным. */
    @ParameterizedTest
    @ValueSource(strings = {" media-object-42", "media-object-42 ", "\tmedia-object-42"})
    void attach_whenObjectIdHasSpaceAtEdge_isRejectedWithThatFieldAndNothingRead(
            String objectId) {
        FileReference spaced = new FileReference(objectId, "отчёт.pdf", 2048, "application/pdf");

        ApiException thrown = catchThrowableOfType(
                () -> service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, spaced),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(ValidationError.ofField("objectId",
                "не должно начинаться или заканчиваться пробелом"));
        verifyNoInteractions(repository);
    }

    @Test
    void attach_whenFileNameLongerThanLimit_isRejectedWithThatField() {
        FileReference longName = new FileReference("media-object-42",
                "я".repeat(RequestAttachment.TEXT_MAX_LENGTH + 1), 2048, "application/pdf");

        ApiException thrown = catchThrowableOfType(
                () -> service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, longName),
                ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("fileName", "должно быть не длиннее 255 символов"));
        verifyNoInteractions(repository);
    }

    @Test
    void attach_whenFileIsEmptyAndNameIsAtLimit_isAccepted() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FileReference atLimit = new FileReference("media-object-42",
                "я".repeat(RequestAttachment.TEXT_MAX_LENGTH), 0, "text/plain");

        assertThat(service.attach(TENANT_ID, REQUEST_ID, AUTHOR_ID, atLimit).getSizeBytes())
                .isZero();
    }

    @Test
    void attachments_readsGivenPageOfGivenTenantsRequest() {
        PageRequest page = PageRequest.of(1, 20);

        service.attachments(TENANT_ID, REQUEST_ID, page);

        verify(repository).findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(TENANT_ID,
                REQUEST_ID, page);
    }

    @Test
    void attachments_whenPageLargerThanLimit_isRejectedWithoutReading() {
        ApiException thrown = catchThrowableOfType(() -> service.attachments(TENANT_ID,
                REQUEST_ID, PageRequest.of(0, PageRequests.MAX_SIZE + 1)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }
}

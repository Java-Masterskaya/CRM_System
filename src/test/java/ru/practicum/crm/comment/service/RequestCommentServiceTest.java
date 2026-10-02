package ru.practicum.crm.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import ru.practicum.crm.comment.domain.RequestComment;
import ru.practicum.crm.comment.repository.RequestCommentRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Сервис без базы: репозиторий — заглушка; хранение и ограничения базы проверяет
 * интеграционный тест.
 */
class RequestCommentServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();

    /** Символ вне основной плоскости Юникода: в строке Java он занимает две единицы char. */
    private static final int EMOJI_CODE_POINT = 0x1F642;

    private final RequestCommentRepository repository = mock(RequestCommentRepository.class);
    private final RequestCommentService service = new RequestCommentService(repository);

    @Test
    void add_whenTextValid_savesCommentWithTenantRequestAuthorAndVisibility() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RequestComment saved = service.add(TENANT_ID, REQUEST_ID, AUTHOR_ID,
                "Данные выгружены, проверьте", true);

        ArgumentCaptor<RequestComment> stored = ArgumentCaptor.forClass(RequestComment.class);
        verify(repository).save(stored.capture());
        assertThat(saved).isSameAs(stored.getValue());
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(saved.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getText()).isEqualTo("Данные выгружены, проверьте");
        assertThat(saved.isVisibleToClient()).isTrue();
    }

    @Test
    void add_whenTextEmptyOrBlank_isRejectedWithTextFieldAndNothingSaved() {
        for (String text : new String[] {null, "", "   \n"}) {
            ApiException thrown = catchThrowableOfType(
                    () -> service.add(TENANT_ID, REQUEST_ID, AUTHOR_ID, text, false),
                    ApiException.class);

            assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.getErrors()).containsExactly(
                    ValidationError.ofField("text", "не должно быть пустым"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void add_whenTextLongerThanLimit_isRejectedWithTextFieldAndNothingSaved() {
        String tooLong = "я".repeat(RequestComment.TEXT_MAX_LENGTH + 1);

        ApiException thrown = catchThrowableOfType(
                () -> service.add(TENANT_ID, REQUEST_ID, AUTHOR_ID, tooLong, false),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("text", "должно быть не длиннее 4000 символов"));
        verifyNoInteractions(repository);
    }

    /**
     * Длина считается в символах, как в базе: эмодзи занимает в строке Java две единицы
     * {@code char}, но это один символ, и 4000 таких символов допустимы.
     */
    @Test
    void add_whenTextIsExactlyAtLimitCountedInSymbols_isAccepted() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String atLimit = Character.toString(EMOJI_CODE_POINT)
                .repeat(RequestComment.TEXT_MAX_LENGTH);

        assertThat(atLimit.length()).isEqualTo(2 * RequestComment.TEXT_MAX_LENGTH);
        assertThat(service.add(TENANT_ID, REQUEST_ID, AUTHOR_ID, atLimit, false).getText())
                .isEqualTo(atLimit);
    }

    @Test
    void comments_readsGivenPageOfGivenTenantsRequest() {
        PageRequest page = PageRequest.of(2, 20);

        service.comments(TENANT_ID, REQUEST_ID, page);

        verify(repository).findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(TENANT_ID,
                REQUEST_ID, page);
    }

    @Test
    void comments_whenPageLargerThanLimit_isRejectedWithoutReading() {
        ApiException thrown = catchThrowableOfType(() -> service.comments(TENANT_ID, REQUEST_ID,
                PageRequest.of(0, PageRequests.MAX_SIZE + 1)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }
}
